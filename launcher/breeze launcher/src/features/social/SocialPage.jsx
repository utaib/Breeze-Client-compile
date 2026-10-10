import { useEffect, useMemo, useRef, useState } from "react";
import { I, CRAFATAR_AVATAR } from "../../ui/icons";
import { isFriendOnline } from "../../services/breezeApi";
import { Panel } from "../../components/Shared";

/** 14:05 today, "Yesterday 14:05" before that, then the date. */
function messageTime(iso) {
  if (!iso) return "";
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return "";
  return at.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

function dayLabel(iso) {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return "";
  const today = new Date();
  const sameDay = (a, b) => a.toDateString() === b.toDateString();
  if (sameDay(at, today)) return "Today";
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (sameDay(at, yesterday)) return "Yesterday";
  return at.toLocaleDateString([], { day: "numeric", month: "short", year: at.getFullYear() === today.getFullYear() ? undefined : "numeric" });
}

/** The other player in a friendship row, whether or not their profile loaded. */
function otherPlayer(row) {
  const uuid = row?.uuid || row?.user?.uuid || null;
  return { uuid, username: row?.user?.username || null, lastSeen: row?.user?.lastSeen || null };
}

function Avatar({ uuid, size = "sm" }) {
  // Heads come from crafatar, which is a third party and does go down. A broken
  // image icon in a friends list looks like Breeze is broken, so it falls back.
  const [failed, setFailed] = useState(false);
  useEffect(() => { setFailed(false); }, [uuid]);
  if (!uuid || failed) return <span className={`avatar-blank ${size}`} aria-hidden="true"><I.User /></span>;
  return <img src={CRAFATAR_AVATAR(uuid)} alt="" className={`avatar-head ${size}`} onError={() => setFailed(true)} />;
}

export default function SocialPage({
  search, setSearch, onSearch, searching, result, onAdd, addBusy,
  friends, requests, session, onAccept, onDecline, onRemove,
  activeFriend, openFriendChat, messages, chatState, draft, setDraft, sendMessage, sending,
  featureFlags,
}) {
  // Every list is normalised up front: social data arrives asynchronously and
  // is cleared on account switch, so any of these can be null mid-render.
  const safeResult = result && typeof result === "object" ? result : { users: [], message: "" };
  const searchUsers = Array.isArray(safeResult.users) ? safeResult.users : [];
  const searchMessage = safeResult.message || "";
  const friendList = Array.isArray(friends) ? friends : [];
  const messageList = Array.isArray(messages) ? messages : [];
  const requestList = Array.isArray(requests) ? requests : [];
  const incoming = requestList.filter((r) => r?.addressee_uuid === session?.uuid);
  const outgoing = requestList.filter((r) => r?.requester_uuid === session?.uuid);

  const [menuFor, setMenuFor] = useState(null);
  const listEnd = useRef(null);
  const listBox = useRef(null);

  // A conversation opens at its newest message, and follows new arrivals while
  // the reader is already at the bottom. Someone scrolled up reading history is
  // left where they are.
  const atBottom = useRef(true);
  useEffect(() => {
    const box = listBox.current;
    if (!box) return undefined;
    const onScroll = () => {
      atBottom.current = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
    };
    box.addEventListener("scroll", onScroll);
    return () => box.removeEventListener("scroll", onScroll);
  }, [activeFriend?.uuid]);

  useEffect(() => {
    if (!listEnd.current) return;
    if (atBottom.current) listEnd.current.scrollIntoView({ block: "end" });
  }, [messageList.length, activeFriend?.uuid]);

  useEffect(() => { atBottom.current = true; }, [activeFriend?.uuid]);

  // The header's online dot reads from the friends list, which the launcher
  // refreshes, rather than from the snapshot taken when the chat was opened.
  const activeRow = friendList.find((f) => otherPlayer(f).uuid === activeFriend?.uuid);
  const activeLastSeen = activeRow ? otherPlayer(activeRow).lastSeen : activeFriend?.lastSeen;
  const activeOnline = isFriendOnline(activeLastSeen);

  const totalUnread = useMemo(
    () => friendList.reduce((sum, f) => sum + (Number(f.unread) || 0), 0),
    [friendList],
  );

  if (featureFlags?.chat_disabled) {
    return (
      <div className="sv page-enter social-unavailable">
        <div className="social-unavailable-card">
          <I.Users />
          <div className="social-unavailable-title">Social is unavailable</div>
          <div className="social-unavailable-sub">Friends and messages are switched off while we work on them.</div>
        </div>
      </div>
    );
  }

  return (
    <div className="sv page-enter ecosystem-page">
      <div className="ecosystem-main">
        <div className="vtl">
          <I.Users /> Social
          {totalUnread > 0 && <span className="req-count">{totalUnread}</span>}
        </div>

        {/* Add a friend */}
        <div className="social-search-row">
          <div className="sw">
            <I.Search />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && onSearch()}
              placeholder="Add a friend by Minecraft username"
            />
          </div>
          <button className="btn accent" onClick={onSearch} disabled={searching || !search.trim()}>
            {searching ? <I.Spin /> : <I.Search />} {searching ? "Searching" : "Search"}
          </button>
        </div>

        {searchMessage && (
          <div className={`social-note ${safeResult.failed ? "bad" : ""}`}>
            {searchMessage}
          </div>
        )}

        {searchUsers.length > 0 && (
          <div className="social-results">
            {searchUsers.map((user) => {
              const already = friendList.some((f) => otherPlayer(f).uuid === user.uuid);
              const pending = requestList.some((r) => r.requester_uuid === user.uuid || r.addressee_uuid === user.uuid);
              return (
                <div key={user.uuid} className="social-result">
                  <Avatar uuid={user.uuid} />
                  <div className="social-result-who">
                    <div className="social-result-name">{user.username}</div>
                    <div className="social-result-state">
                      <span className={`status-dot ${isFriendOnline(user.lastSeen) ? "online" : "offline"}`} />
                      {isFriendOnline(user.lastSeen) ? "Online" : "Offline"}
                    </div>
                  </div>
                  {already ? (
                    <span className="social-result-tag">Friends</span>
                  ) : pending ? (
                    <span className="social-result-tag">Requested</span>
                  ) : (
                    <button
                      className="btn"
                      disabled={addBusy === `add:${user.uuid}`}
                      onClick={() => onAdd({ username: user.username, uuid: user.uuid })}
                    >
                      {addBusy === `add:${user.uuid}` ? <I.Spin /> : <I.Plus />} Add
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        )}

        <div className="social-layout">
          {/* Friends and requests */}
          <div className="social-list">
            {incoming.length > 0 && (
              <>
                <div className="panel-title">Requests <span className="req-count">{incoming.length}</span></div>
                {incoming.map((r) => {
                  const who = otherPlayer(r);
                  return (
                    <div key={r.id} className="request-row incoming">
                      <Avatar uuid={who.uuid} />
                      <span className="req-name">{who.username || "Breeze player"}</span>
                      <div className="req-actions">
                        <button className="mini-btn accept" title="Accept" onClick={() => onAccept(r.id)}><I.Check /></button>
                        <button className="mini-btn decline" title="Decline" onClick={() => onDecline?.(r.id)}><I.X /></button>
                      </div>
                    </div>
                  );
                })}
              </>
            )}

            <div className="panel-title">
              Friends {friendList.length > 0 && <span className="req-count muted">{friendList.length}</span>}
            </div>
            {friendList.length ? (
              friendList.map((f) => {
                const who = otherPlayer(f);
                const unread = Number(f.unread) || 0;
                return (
                  <div key={f.id} className={`friend-row-wrap ${activeFriend?.uuid === who.uuid ? "on" : ""}`}>
                    <button className="friend-row" onClick={() => openFriendChat(f)}>
                      <Avatar uuid={who.uuid} />
                      <span className="friend-name">{who.username || "Breeze player"}</span>
                      <span className={`status-dot ${isFriendOnline(who.lastSeen) ? "online" : "offline"}`} />
                      {unread > 0 && <span className="req-count">{unread > 99 ? "99+" : unread}</span>}
                    </button>
                    <button
                      className="mini-btn friend-menu-btn"
                      title="More"
                      onClick={() => setMenuFor(menuFor === who.uuid ? null : who.uuid)}
                    >
                      <I.Sliders />
                    </button>
                    {menuFor === who.uuid && (
                      <div className="friend-menu">
                        <button onClick={() => { setMenuFor(null); onRemove?.(who.uuid, { block: false }); }}>
                          Remove friend
                        </button>
                        <button className="bad" onClick={() => { setMenuFor(null); onRemove?.(who.uuid, { block: true }); }}>
                          Block
                        </button>
                      </div>
                    )}
                  </div>
                );
              })
            ) : (
              <div className="empty-panel">Search for a player above to add your first friend.</div>
            )}

            {outgoing.length > 0 && (
              <>
                <div className="panel-title spaced">Sent</div>
                {outgoing.map((r) => (
                  <div key={r.id} className="request-row pending">
                    <Avatar uuid={otherPlayer(r).uuid} />
                    <span className="req-name">{otherPlayer(r).username || "Breeze player"}</span>
                    <span className="req-status">Waiting</span>
                  </div>
                ))}
              </>
            )}
          </div>

          {/* Conversation */}
          <div className="chat-panel">
            {activeFriend ? (
              <>
                <div className="chat-head">
                  <Avatar uuid={activeFriend.uuid} size="md" />
                  <div className="si">
                    <div className="sn">{activeFriend.username || "Breeze player"}</div>
                    <div className="sd">
                      <span className={`status-dot ${activeOnline ? "online" : "offline"}`} />
                      {activeOnline ? "Online now" : "Offline"}
                    </div>
                  </div>
                </div>

                <div className="message-list" ref={listBox}>
                  {chatState?.loading && <div className="chat-state"><I.Spin /> Loading conversation</div>}
                  {chatState?.error && <div className="chat-state bad">{chatState.error}</div>}
                  {!chatState?.loading && !chatState?.error && messageList.length === 0 && (
                    <div className="empty-panel">
                      No messages yet. Say hello to {activeFriend.username || "them"}.
                    </div>
                  )}
                  {messageList.map((m, index) => {
                    const mine = m.sender_uuid === session?.uuid;
                    const previous = messageList[index - 1];
                    const newDay = !previous || dayLabel(previous.created_at) !== dayLabel(m.created_at);
                    return (
                      <div key={m.id} className="message-group">
                        {newDay && m.created_at && <div className="message-day">{dayLabel(m.created_at)}</div>}
                        <div className={`message-bubble ${mine ? "mine" : ""} ${m.failed ? "failed" : ""}`}>
                          <span className="message-body">{m.body}</span>
                          <span className="message-meta">
                            {m.pending ? "Sending" : m.failed ? "Not sent" : messageTime(m.created_at)}
                            {mine && !m.pending && !m.failed && m.read_at && <span className="message-read" title="Read"><I.Check /></span>}
                          </span>
                        </div>
                      </div>
                    );
                  })}
                  <div ref={listEnd} />
                </div>

                <div className="chat-input-row">
                  <input
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendMessage(); } }}
                    placeholder={`Message ${activeFriend.username || "your friend"}`}
                    maxLength={4000}
                  />
                  <button className="btn accent" onClick={sendMessage} disabled={sending || !draft.trim()} title="Send">
                    {sending ? <I.Spin /> : <I.Arrow />}
                  </button>
                </div>
              </>
            ) : (
              <div className="chat-empty">
                <I.Users />
                <div className="chat-empty-title">Pick a friend</div>
                <div className="chat-empty-sub">Their messages open here. Nobody else can read them.</div>
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="ecosystem-side">
        <Panel title="Social">
          <div className="side-copy">
            Friends you add here are the same friends Breeze shows in game. Messages are
            between the two of you, and only a friend can message you.
          </div>
        </Panel>
      </div>
    </div>
  );
}
