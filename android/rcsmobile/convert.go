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

package rcsmobile

import (
	"strings"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// These are the JSON shapes handed to the Android app. Timestamps are Unix milliseconds.

type participant struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Number string `json:"number"`
	IsMe   bool   `json:"isMe"`
	Color  string `json:"color"`
}

type conversation struct {
	ID           string        `json:"id"`
	Name         string        `json:"name"`
	Snippet      string        `json:"snippet"`
	SnippetFrom  string        `json:"snippetFrom"`
	FromMe       bool          `json:"fromMe"`
	Timestamp    int64         `json:"timestamp"`
	Unread       bool          `json:"unread"`
	IsGroup      bool          `json:"isGroup"`
	IsRCS        bool          `json:"isRcs"`
	ReadOnly     bool          `json:"readOnly"`
	Pinned       bool          `json:"pinned"`
	Archived     bool          `json:"archived"`
	Color        string        `json:"color"`
	Participants []participant `json:"participants"`
}

type media struct {
	MediaID  string `json:"mediaId"`
	Key      []byte `json:"key"`
	ThumbID  string `json:"thumbId"`
	ThumbKey []byte `json:"thumbKey"`
	Mime     string `json:"mime"`
	Name     string `json:"name"`
	Size     int64  `json:"size"`
	Width    int64  `json:"width"`
	Height   int64  `json:"height"`
}

type reaction struct {
	Emoji  string `json:"emoji"`
	Count  int    `json:"count"`
	FromMe bool   `json:"fromMe"`
}

type message struct {
	ID             string     `json:"id"`
	ConversationID string     `json:"conversationId"`
	TmpID          string     `json:"tmpId"`
	Timestamp      int64      `json:"timestamp"`
	FromMe         bool       `json:"fromMe"`
	SenderID       string     `json:"senderId"`
	SenderName     string     `json:"senderName"`
	Text           string     `json:"text"`
	Media          []media    `json:"media"`
	Status         string     `json:"status"`
	Failed         bool       `json:"failed"`
	Deleted        bool       `json:"deleted"`
	IsRCS          bool       `json:"isRcs"`
	ReplyTo        string     `json:"replyTo"`
	Reactions      []reaction `json:"reactions"`
	IsOld          bool       `json:"isOld"`
}

type cursor struct {
	LastItemID        string `json:"lastItemId"`
	LastItemTimestamp int64  `json:"lastItemTimestamp"`
}

type messagePage struct {
	Messages []message `json:"messages"`
	Cursor   *cursor   `json:"cursor"`
}

type typing struct {
	ConversationID string `json:"conversationId"`
	Number         string `json:"number"`
	Typing         bool   `json:"typing"`
}

func convertConversation(conv *gmproto.Conversation) conversation {
	out := conversation{
		ID:          conv.GetConversationID(),
		Name:        conv.GetName(),
		Snippet:     conv.GetLatestMessage().GetDisplayContent(),
		SnippetFrom: conv.GetLatestMessage().GetDisplayName(),
		FromMe:      conv.GetLatestMessage().GetFromMe() == 1,
		Timestamp:   conv.GetLastMessageTimestamp() / 1000,
		Unread:      conv.GetUnread(),
		IsGroup:     conv.GetIsGroupChat(),
		IsRCS:       conv.GetType() == gmproto.ConversationType_RCS,
		ReadOnly:    conv.GetReadOnly(),
		Pinned:      conv.GetPinned(),
		Archived:    conv.GetStatus() == gmproto.ConversationStatus_ARCHIVED || conv.GetStatus() == gmproto.ConversationStatus_KEEP_ARCHIVED,
		Color:       conv.GetAvatarHexColor(),
	}
	for _, p := range conv.GetParticipants() {
		out.Participants = append(out.Participants, participant{
			ID:     p.GetID().GetParticipantID(),
			Name:   p.GetFullName(),
			Number: p.GetFormattedNumber(),
			IsMe:   p.GetIsMe(),
			Color:  p.GetAvatarHexColor(),
		})
	}
	return out
}

func (c *Client) isFromMe(msg *gmproto.Message) bool {
	if msg.GetSenderParticipant() != nil {
		return msg.GetSenderParticipant().GetIsMe()
	}
	pid := msg.GetParticipantID()
	if pid == "1" {
		return true
	}
	c.convLock.RLock()
	conv := c.convs[msg.GetConversationID()]
	c.convLock.RUnlock()
	if conv != nil && pid != "" && pid == conv.GetDefaultOutgoingID() {
		return true
	}
	status := msg.GetMessageStatus().GetStatus()
	return status > 0 && status < gmproto.MessageStatusType_INCOMING_COMPLETE
}

func (c *Client) senderName(msg *gmproto.Message) string {
	if name := msg.GetSenderParticipant().GetFullName(); name != "" {
		return name
	}
	c.convLock.RLock()
	conv := c.convs[msg.GetConversationID()]
	c.convLock.RUnlock()
	for _, p := range conv.GetParticipants() {
		if p.GetID().GetParticipantID() == msg.GetParticipantID() {
			if p.GetFullName() != "" {
				return p.GetFullName()
			}
			return p.GetFormattedNumber()
		}
	}
	return ""
}

func (c *Client) convertMessage(msg *gmproto.Message, isOld bool) message {
	status := msg.GetMessageStatus().GetStatus()
	out := message{
		ID:             msg.GetMessageID(),
		ConversationID: msg.GetConversationID(),
		TmpID:          msg.GetTmpID(),
		Timestamp:      msg.GetTimestamp() / 1000,
		FromMe:         c.isFromMe(msg),
		SenderID:       msg.GetParticipantID(),
		SenderName:     c.senderName(msg),
		Status:         strings.ToLower(status.String()),
		Deleted:        status == gmproto.MessageStatusType_MESSAGE_DELETED || status == gmproto.MessageStatusType_OUTGOING_DELETED,
		IsRCS:          msg.GetType() == 4,
		ReplyTo:        msg.GetReplyMessage().GetMessageID(),
		IsOld:          isOld,
	}
	switch status {
	case gmproto.MessageStatusType_OUTGOING_FAILED_GENERIC,
		gmproto.MessageStatusType_OUTGOING_FAILED_EMERGENCY_NUMBER,
		gmproto.MessageStatusType_OUTGOING_FAILED_TOO_LARGE,
		gmproto.MessageStatusType_OUTGOING_FAILED_RECIPIENT_LOST_RCS,
		gmproto.MessageStatusType_OUTGOING_FAILED_NO_RETRY_NO_FALLBACK,
		gmproto.MessageStatusType_OUTGOING_FAILED_RECIPIENT_DID_NOT_DECRYPT,
		gmproto.MessageStatusType_OUTGOING_FAILED_RECIPIENT_LOST_ENCRYPTION,
		gmproto.MessageStatusType_OUTGOING_FAILED_RECIPIENT_DID_NOT_DECRYPT_NO_MORE_RETRY,
		gmproto.MessageStatusType_OUTGOING_FAILED_RECIPIENT_NEGATIVE_DELIVERY,
		gmproto.MessageStatusType_OUTGOING_FAILED_TO_ENCRYPT,
		gmproto.MessageStatusType_OUTGOING_CANCELED:
		out.Failed = true
	}
	var text []string
	for _, info := range msg.GetMessageInfo() {
		if content := info.GetMessageContent(); content != nil && content.GetContent() != "" {
			text = append(text, content.GetContent())
		}
		if mc := info.GetMediaContent(); mc != nil {
			out.Media = append(out.Media, media{
				MediaID:  mc.GetMediaID(),
				Key:      mc.GetDecryptionKey(),
				ThumbID:  mc.GetThumbnailMediaID(),
				ThumbKey: mc.GetThumbnailDecryptionKey(),
				Mime:     mc.GetMimeType(),
				Name:     mc.GetMediaName(),
				Size:     mc.GetSize(),
				Width:    mc.GetDimensions().GetWidth(),
				Height:   mc.GetDimensions().GetHeight(),
			})
		}
	}
	out.Text = strings.Join(text, "\n")
	for _, r := range msg.GetReactions() {
		emoji := r.GetData().GetUnicode()
		if emoji == "" {
			emoji = r.GetData().GetType().Unicode()
		}
		if emoji == "" {
			continue
		}
		reaction := reaction{Emoji: emoji, Count: len(r.GetParticipantIDs())}
		for _, pid := range r.GetParticipantIDs() {
			if pid == "1" || c.isOwnParticipant(msg.GetConversationID(), pid) {
				reaction.FromMe = true
			}
		}
		out.Reactions = append(out.Reactions, reaction)
	}
	return out
}

func (c *Client) isOwnParticipant(convID, pid string) bool {
	c.convLock.RLock()
	conv := c.convs[convID]
	c.convLock.RUnlock()
	if conv == nil {
		return false
	}
	if pid == conv.GetDefaultOutgoingID() {
		return true
	}
	for _, p := range conv.GetParticipants() {
		if p.GetIsMe() && p.GetID().GetParticipantID() == pid {
			return true
		}
	}
	return false
}
