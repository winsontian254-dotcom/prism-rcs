// prism-rcs - an RCS-only Google Messages client for Android.
// Copyright (C) 2026 prism-rcs contributors
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.

// Package rcsmobile is a thin, gomobile-bindable wrapper around libgm.
//
// Only simple types cross the language boundary: strings, bytes, bools and
// int64s. Structured data is passed as JSON. Every method that talks to the
// network blocks, so the Android side must call them off the main thread.
package rcsmobile

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync"

	"github.com/google/uuid"
	"github.com/rs/zerolog"
	"go.mau.fi/util/exhttp"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// Listener receives events from the client. Kind is one of:
//
//	"session"        the stored session changed and must be saved again (json: "")
//	"message"        a message was added or updated (json: Message)
//	"conversation"   a conversation was added or updated (json: Conversation)
//	"typing"         someone started or stopped typing (json: Typing)
//	"connected"      long polling is up (json: "")
//	"phone_offline"  the phone stopped responding (json: "")
//	"phone_online"   the phone is responding again (json: "")
//	"logged_out"     the pairing is gone; the app must unlink (json: {"error": "..."})
//	"error"          a temporary error the UI may show (json: {"error": "..."})
//
// OnEvent is called from Go goroutines and must return quickly.
type Listener interface {
	OnEvent(kind string, json string)
	OnLog(level string, line string)
}

type Client struct {
	listener Listener
	log      zerolog.Logger
	cli      *libgm.Client

	convLock sync.RWMutex
	convs    map[string]*gmproto.Conversation

	pairLock   sync.Mutex
	pairSess   *libgm.PairingSession
	pairCancel context.CancelFunc

	connCtx    context.Context
	connCancel context.CancelFunc
}

type logWriter struct{ l Listener }

func (w logWriter) Write(p []byte) (int, error) {
	var parsed struct {
		Level string `json:"level"`
	}
	_ = json.Unmarshal(p, &parsed)
	w.l.OnLog(parsed.Level, strings.TrimSpace(string(p)))
	return len(p), nil
}

// NewClient creates a client from a saved session, or an empty one when session is empty.
func NewClient(session []byte, listener Listener) (*Client, error) {
	if listener == nil {
		return nil, errors.New("listener is nil")
	}
	c := &Client{
		listener: listener,
		log:      zerolog.New(logWriter{listener}).Level(zerolog.InfoLevel).With().Timestamp().Logger(),
		convs:    make(map[string]*gmproto.Conversation),
	}
	if len(session) > 0 {
		ad := libgm.NewAuthData()
		if err := json.Unmarshal(session, ad); err != nil {
			return nil, fmt.Errorf("failed to parse session: %w", err)
		}
		c.cli = c.newLibgmClient(ad)
	}
	return c, nil
}

func (c *Client) newLibgmClient(ad *libgm.AuthData) *libgm.Client {
	cli := libgm.NewClient(ad, nil, c.log.With().Str("component", "libgm").Logger(), exhttp.SensibleClientSettings)
	return cli
}

func (c *Client) ctx() context.Context {
	return c.log.WithContext(context.Background())
}

func (c *Client) emit(kind string, v any) {
	if v == nil {
		c.listener.OnEvent(kind, "")
		return
	}
	data, err := json.Marshal(v)
	if err != nil {
		c.log.Err(err).Str("kind", kind).Msg("Failed to marshal event")
		return
	}
	c.listener.OnEvent(kind, string(data))
}

type errorPayload struct {
	Error string `json:"error"`
}

// IsLoggedIn reports whether a paired session is loaded.
func (c *Client) IsLoggedIn() bool {
	return c.cli != nil && c.cli.IsLoggedIn()
}

// Session returns the current session as JSON for the app to store encrypted.
func (c *Client) Session() ([]byte, error) {
	if c.cli == nil {
		return nil, errors.New("not logged in")
	}
	return json.Marshal(c.cli.AuthData)
}

// StartPairing begins Google account pairing with the cookies taken from the
// sign-in WebView (a JSON object of cookie name to value). It returns the emoji
// the user must tap in Google Messages on their phone; call WaitPairing next.
func (c *Client) StartPairing(cookiesJSON string) (string, error) {
	var cookies map[string]string
	if err := json.Unmarshal([]byte(cookiesJSON), &cookies); err != nil {
		return "", fmt.Errorf("failed to parse cookies: %w", err)
	}
	for _, name := range []string{"SID", "HSID", "SSID", "APISID", "SAPISID", "OSID"} {
		if cookies[name] == "" {
			return "", fmt.Errorf("missing %s cookie, please sign in again", name)
		}
	}
	c.CancelPairing()
	c.pairLock.Lock()
	defer c.pairLock.Unlock()

	ad := libgm.NewAuthData()
	ad.Cookies = cookies
	cli := c.newLibgmClient(ad)
	cli.SetEventHandler(func(evt any) {})
	ctx := c.ctx()
	if err := cli.FetchConfig(ctx); err != nil {
		return "", fmt.Errorf("couldn't reach Google Messages: %w", err)
	}
	bgCtx, cancel := context.WithCancel(ctx)
	emoji, sess, err := cli.StartGaiaPairing(ctx, bgCtx)
	if err != nil {
		cancel()
		cli.Disconnect()
		return "", pairingStartError(err)
	}
	c.cli = cli
	c.pairSess = sess
	c.pairCancel = cancel
	return emoji, nil
}

func pairingStartError(err error) error {
	switch {
	case errors.Is(err, libgm.ErrNoDevicesFound):
		return errors.New("no phone found: open Google Messages on your phone with this Google account and turn on device pairing")
	case errors.Is(err, libgm.ErrPairingInitTimeout):
		return errors.New("your phone didn't respond: open Google Messages on it and try again")
	case errors.Is(err, events.ErrCallerNoPermission):
		return errors.New("this Google account can't use Google Messages for web")
	default:
		return fmt.Errorf("couldn't start pairing: %w", err)
	}
}

// WaitPairing blocks until the user confirms the emoji on their phone. On
// success the client is connected and a "session" event is emitted.
func (c *Client) WaitPairing() error {
	c.pairLock.Lock()
	cli, sess, cancel := c.cli, c.pairSess, c.pairCancel
	c.pairLock.Unlock()
	if cli == nil || sess == nil {
		return errors.New("pairing not started")
	}
	_, err := cli.FinishGaiaPairing(c.ctx(), sess)
	if cancel != nil {
		cancel()
	}
	cli.Disconnect()
	c.pairLock.Lock()
	c.pairSess, c.pairCancel = nil, nil
	c.pairLock.Unlock()
	if err != nil {
		c.cli = nil
		switch {
		case errors.Is(err, libgm.ErrIncorrectEmoji):
			return errors.New("the wrong emoji was tapped on your phone, please try again")
		case errors.Is(err, libgm.ErrPairingCancelled):
			return errors.New("pairing was cancelled on your phone")
		case errors.Is(err, libgm.ErrPairingTimeout):
			return errors.New("pairing timed out, please try again")
		default:
			return fmt.Errorf("pairing failed: %w", err)
		}
	}
	// Start again with a fresh client for the paired session, like the bridge does.
	c.cli = c.newLibgmClient(cli.AuthData)
	c.emit("session", nil)
	return c.Connect()
}

// CancelPairing aborts a pairing that StartPairing began.
func (c *Client) CancelPairing() {
	c.pairLock.Lock()
	defer c.pairLock.Unlock()
	if c.pairCancel != nil {
		c.pairCancel()
		c.pairCancel = nil
	}
	if c.pairSess != nil && c.cli != nil {
		c.cli.Disconnect()
		c.cli = nil
	}
	c.pairSess = nil
}

// Connect starts receiving events for a paired session.
func (c *Client) Connect() error {
	if !c.IsLoggedIn() {
		return errors.New("not logged in")
	}
	c.Disconnect()
	c.connCtx, c.connCancel = context.WithCancel(c.ctx())
	c.cli.SetEventHandler(c.handleEvent)
	if err := c.cli.FetchConfig(c.connCtx); err != nil {
		return fmt.Errorf("couldn't reach Google Messages: %w", err)
	}
	if err := c.cli.Connect(c.connCtx); err != nil {
		return err
	}
	c.emit("session", nil)
	c.emit("connected", nil)
	return nil
}

// Disconnect stops receiving events but keeps the session.
func (c *Client) Disconnect() {
	if c.connCancel != nil {
		c.connCancel()
		c.connCancel = nil
	}
	if c.cli != nil {
		c.cli.Disconnect()
	}
}

// IsConnected reports whether long polling is currently running.
func (c *Client) IsConnected() bool {
	return c.cli != nil && c.cli.IsConnected()
}

// Unlink removes this device from the user's Google Messages and forgets the
// session. The app must also delete its stored copy.
func (c *Client) Unlink() error {
	c.CancelPairing()
	if c.cli == nil {
		return nil
	}
	var err error
	if c.cli.IsLoggedIn() {
		err = c.cli.Unpair(c.ctx())
	}
	c.Disconnect()
	c.cli.SetEventHandler(nil)
	c.cli = nil
	c.convLock.Lock()
	clear(c.convs)
	c.convLock.Unlock()
	return err
}

func (c *Client) handleEvent(rawEvt any) {
	switch evt := rawEvt.(type) {
	case *events.AuthTokenRefreshed, *events.PairSuccessful:
		c.emit("session", nil)
	case *events.ListenRecovered:
		c.emit("connected", nil)
	case *events.PhoneNotResponding:
		c.emit("phone_offline", nil)
	case *events.PhoneRespondingAgain:
		c.emit("phone_online", nil)
	case *events.GaiaLoggedOut:
		c.emit("logged_out", errorPayload{"you were signed out of Google"})
	case *gmproto.RevokePairData:
		c.emit("logged_out", errorPayload{"this device was unlinked from your phone"})
	case *events.ListenFatalError:
		c.emit("logged_out", errorPayload{evt.Error.Error()})
	case *events.ListenTemporaryError:
		c.emit("error", errorPayload{evt.Error.Error()})
	case *events.PingFailed:
		c.emit("error", errorPayload{evt.Error.Error()})
	case *gmproto.Conversation:
		c.cacheConversation(evt)
		c.emit("conversation", convertConversation(evt))
	case *libgm.WrappedMessage:
		c.emit("message", c.convertMessage(evt.Message, evt.IsOld))
	case *gmproto.TypingData:
		c.emit("typing", typing{
			ConversationID: evt.GetConversationID(),
			Number:         evt.GetUser().GetNumber(),
			Typing:         evt.GetType() == gmproto.TypingTypes_STARTED_TYPING,
		})
	}
}

func (c *Client) cacheConversation(conv *gmproto.Conversation) {
	c.convLock.Lock()
	c.convs[conv.GetConversationID()] = conv
	c.convLock.Unlock()
}

func (c *Client) conversation(ctx context.Context, convID string) (*gmproto.Conversation, error) {
	c.convLock.RLock()
	conv, ok := c.convs[convID]
	c.convLock.RUnlock()
	if ok {
		return conv, nil
	}
	conv, err := c.cli.GetConversation(ctx, convID)
	if err != nil {
		return nil, err
	}
	c.cacheConversation(conv)
	return conv, nil
}

func (c *Client) requireLogin() error {
	if !c.IsLoggedIn() {
		return errors.New("not logged in")
	}
	return nil
}

// ListConversations returns a JSON array of Conversation, newest first. With
// rcsOnly, plain SMS/MMS threads are left out.
func (c *Client) ListConversations(count int64, rcsOnly bool) (string, error) {
	if err := c.requireLogin(); err != nil {
		return "", err
	}
	resp, err := c.cli.ListConversations(c.ctx(), &gmproto.ListConversationsRequest{
		Count:  count,
		Folder: gmproto.ListConversationsRequest_INBOX,
	})
	if err != nil {
		return "", err
	}
	out := make([]conversation, 0, len(resp.GetConversations()))
	for _, conv := range resp.GetConversations() {
		c.cacheConversation(conv)
		if rcsOnly && conv.GetType() != gmproto.ConversationType_RCS {
			continue
		}
		out = append(out, convertConversation(conv))
	}
	return marshal(out)
}

// FetchMessages returns a JSON MessagePage. Pass the previous page's cursor
// (or "" for the newest messages) to page back in time.
func (c *Client) FetchMessages(convID string, count int64, cursorJSON string) (string, error) {
	if err := c.requireLogin(); err != nil {
		return "", err
	}
	var gmCursor *gmproto.Cursor
	if cursorJSON != "" {
		var cur cursor
		if err := json.Unmarshal([]byte(cursorJSON), &cur); err != nil {
			return "", err
		}
		gmCursor = &gmproto.Cursor{LastItemID: cur.LastItemID, LastItemTimestamp: cur.LastItemTimestamp}
	}
	resp, err := c.cli.FetchMessages(c.ctx(), convID, count, gmCursor)
	if err != nil {
		return "", err
	}
	page := messagePage{Messages: make([]message, 0, len(resp.GetMessages()))}
	for _, msg := range resp.GetMessages() {
		page.Messages = append(page.Messages, c.convertMessage(msg, true))
	}
	if cur := resp.GetCursor(); cur != nil && cur.GetLastItemID() != "" {
		page.Cursor = &cursor{LastItemID: cur.GetLastItemID(), LastItemTimestamp: cur.GetLastItemTimestamp()}
	}
	return marshal(page)
}

func (c *Client) newSendRequest(ctx context.Context, convID, replyTo string) (*gmproto.SendMessageRequest, error) {
	conv, err := c.conversation(ctx, convID)
	if err != nil {
		return nil, fmt.Errorf("couldn't load conversation: %w", err)
	}
	tmpID := "tmp_" + strings.ReplaceAll(uuid.NewString(), "-", "")[:12]
	req := &gmproto.SendMessageRequest{
		ConversationID: convID,
		MessagePayload: &gmproto.MessagePayload{
			TmpID:          tmpID,
			ConversationID: convID,
			ParticipantID:  conv.GetDefaultOutgoingID(),
			TmpID2:         tmpID,
		},
		SIMPayload: conv.GetSimCard().GetSIMData().GetSIMPayload(),
		TmpID:      tmpID,
	}
	if replyTo != "" {
		req.Reply = &gmproto.ReplyPayload{MessageID: replyTo}
	}
	return req, nil
}

func (c *Client) send(ctx context.Context, req *gmproto.SendMessageRequest) (string, error) {
	resp, err := c.cli.SendMessage(ctx, req)
	if err != nil {
		return "", err
	} else if resp.GetStatus() != gmproto.SendMessageResponse_SUCCESS {
		return "", fmt.Errorf("your phone couldn't send the message (%s)", resp.GetStatus())
	}
	return req.TmpID, nil
}

// SendText sends a text message and returns its temporary ID. The real message
// arrives later as a "message" event carrying the same tmpId.
func (c *Client) SendText(convID, text, replyTo string) (string, error) {
	if err := c.requireLogin(); err != nil {
		return "", err
	}
	ctx := c.ctx()
	req, err := c.newSendRequest(ctx, convID, replyTo)
	if err != nil {
		return "", err
	}
	req.MessagePayload.MessageInfo = []*gmproto.MessageInfo{{
		Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: text}},
	}}
	return c.send(ctx, req)
}

// SendMedia uploads a file and sends it, with an optional caption.
func (c *Client) SendMedia(convID string, data []byte, fileName, mime, caption string) (string, error) {
	if err := c.requireLogin(); err != nil {
		return "", err
	}
	ctx := c.ctx()
	req, err := c.newSendRequest(ctx, convID, "")
	if err != nil {
		return "", err
	}
	media, err := c.cli.UploadMedia(ctx, data, fileName, mime)
	if err != nil {
		return "", fmt.Errorf("upload failed: %w", err)
	}
	req.MessagePayload.MessageInfo = []*gmproto.MessageInfo{{
		Data: &gmproto.MessageInfo_MediaContent{MediaContent: media},
	}}
	if caption != "" {
		req.MessagePayload.MessageInfo = append(req.MessagePayload.MessageInfo, &gmproto.MessageInfo{
			Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: caption}},
		})
	}
	return c.send(ctx, req)
}

// DownloadMedia fetches and decrypts an attachment. key is the base64 key from the Media JSON.
func (c *Client) DownloadMedia(mediaID string, key []byte) ([]byte, error) {
	if err := c.requireLogin(); err != nil {
		return nil, err
	}
	reader, err := c.cli.DownloadMedia(c.ctx(), mediaID, key)
	if err != nil {
		return nil, err
	}
	defer reader.Close()
	return io.ReadAll(reader)
}

// React adds, replaces or removes (remove=true) your reaction on a message.
func (c *Client) React(convID, messageID, emoji string, replace, remove bool) error {
	if err := c.requireLogin(); err != nil {
		return err
	}
	ctx := c.ctx()
	action := gmproto.SendReactionRequest_ADD
	if remove {
		action = gmproto.SendReactionRequest_REMOVE
	} else if replace {
		action = gmproto.SendReactionRequest_SWITCH
	}
	req := &gmproto.SendReactionRequest{
		MessageID:    messageID,
		ReactionData: gmproto.MakeReactionData(emoji),
		Action:       action,
	}
	if conv, err := c.conversation(ctx, convID); err == nil {
		req.SIMPayload = conv.GetSimCard().GetSIMData().GetSIMPayload()
	}
	resp, err := c.cli.SendReaction(ctx, req)
	if err != nil {
		return err
	} else if !resp.GetSuccess() {
		return errors.New("your phone rejected the reaction")
	}
	return nil
}

// SetTyping tells the other side you are typing. Call it at most every few seconds.
func (c *Client) SetTyping(convID string) error {
	if err := c.requireLogin(); err != nil {
		return err
	}
	ctx := c.ctx()
	conv, err := c.conversation(ctx, convID)
	if err != nil {
		return err
	}
	return c.cli.SetTyping(ctx, convID, conv.GetSimCard().GetSIMData().GetSIMPayload())
}

// MarkRead sends a read receipt up to the given message.
func (c *Client) MarkRead(convID, messageID string) error {
	if err := c.requireLogin(); err != nil {
		return err
	}
	return c.cli.MarkRead(c.ctx(), convID, messageID)
}

// DeleteMessage deletes a message on the phone (for you only).
func (c *Client) DeleteMessage(messageID string) error {
	if err := c.requireLogin(); err != nil {
		return err
	}
	resp, err := c.cli.DeleteMessage(c.ctx(), messageID)
	if err != nil {
		return err
	} else if !resp.GetSuccess() {
		return errors.New("your phone couldn't delete the message")
	}
	return nil
}

func marshal(v any) (string, error) {
	data, err := json.Marshal(v)
	return string(data), err
}
