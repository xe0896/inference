import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import "katex/dist/katex.min.css";
import Auth from "./Auth.jsx";
import Usage, {
    ContextRing,
    DEFAULT_CONTEXT_LIMIT,
    fetchUsage,
} from "./Usage.jsx";
import "./App.css";

let nextId = 0;

const emptySession = { prompt: 0, completion: 0, requests: 0, compactions: 0 };

// Models often emit \( \) and \[ \] delimiters; remark-math only understands $ / $$.
// Convert them, leaving fenced and inline code untouched.
function normalizeMath(text) {
    return text
        .split(/(```[\s\S]*?(?:```|$)|`[^`\n]*`)/g)
        .map((part, i) =>
            i % 2
                ? part
                : part
                      .replace(
                          /\\\[([\s\S]+?)\\\]/g,
                          (_, m) => `\n$$\n${m.trim()}\n$$\n`,
                      )
                      .replace(
                          /\\\(([\s\S]+?)\\\)/g,
                          (_, m) => `$${m.trim()}$`,
                      ),
        )
        .join("");
}

// Messages sent as context. Everything is still rendered, but once the backend
// has summarised a turn, the summary stands in for all messages before the
// prompt that triggered it. That prompt is kept so the list goes
// summary -> user -> assistant (llama-server rejects consecutive assistants).
function buildContext(messages) {
    let start = 0;
    let base = [];
    for (let i = messages.length - 1; i >= 0; i--) {
        if (messages[i].summary) {
            start = i - 1;
            base = [{ role: "assistant", content: messages[i].summary }];
            break;
        }
    }
    return [
        ...base,
        ...messages
            .slice(start)
            .map((m) => ({ role: m.role, content: m.text })),
    ];
}

export default function App() {
    const [messages, setMessages] = useState([]);
    const [input, setInput] = useState("");
    const [busy, setBusy] = useState(false);
    const [maxTokens, setMaxTokens] = useState(8192);
    const [temperature, setTemperature] = useState(0.7);
    const [thinking, setThinking] = useState(true);
    const [user, setUser] = useState(null);
    const [authOpen, setAuthOpen] = useState(false);
    const [init, setInit] = useState(true);
    const [usageOpen, setUsageOpen] = useState(false);
    // Running totals for this page session, built from `done` events
    const [session, setSession] = useState(emptySession);
    // Size of the context the next request will send, from /tokens
    const [context, setContext] = useState(0);
    // All-time figures and context limit from /usage
    const [usage, setUsage] = useState(null);
    const [usageError, setUsageError] = useState("");
    const modalOpen = authOpen || usageOpen;
    const closeUsage = useCallback(() => setUsageOpen(false), []);
    const contextLimit = usage?.context_length || DEFAULT_CONTEXT_LIMIT;

    const refreshUsage = useCallback(() => {
        fetchUsage()
            .then((u) => {
                setUsage(u);
                setUsageError("");
            })
            .catch((err) => setUsageError(err.message));
    }, []);

    // Load on login and whenever the dialog opens
    useEffect(() => {
        if (user) refreshUsage();
    }, [user, refreshUsage]);

    useEffect(() => {
        if (usageOpen) refreshUsage();
    }, [usageOpen, refreshUsage]);

    const abortRef = useRef(null);
    const listRef = useRef(null);
    const stickRef = useRef(true);
    const inputRef = useRef(null);

    // Follow the stream only while the user is already at the bottom
    useEffect(() => {
        const el = listRef.current;
        if (el && stickRef.current) el.scrollTop = el.scrollHeight;
    }, [messages]);

    // Grow the textarea with its content
    useEffect(() => {
        const el = inputRef.current;
        if (!el) return;
        el.style.height = "auto";
        el.style.height = `${el.scrollHeight}px`;
    }, [input]);

    useEffect(() => {
        setAuthOpen(true);
    }, []);

    function onScroll() {
        const el = listRef.current;
        stickRef.current =
            el.scrollHeight - el.scrollTop - el.clientHeight < 40;
    }

    function patch(id, change) {
        setMessages((prev) =>
            prev.map((m) => (m.id === id ? { ...m, ...change(m) } : m)),
        );
    }

    async function send() {
        const prompt = input.trim();
        if (!prompt || busy) return;
        if (!user) {
            setAuthOpen(true);
            return;
        }

        const replyId = nextId++;
        setMessages((prev) => [
            ...prev,
            { id: nextId++, role: "user", text: prompt },
            {
                id: replyId,
                role: "assistant",
                reasoning: "",
                text: "",
                pending: true,
                // null = follow streaming state; true/false = user override
                reasoningOpen: null,
            },
        ]);
        setInput("");
        setBusy(true);
        stickRef.current = true;

        const controller = new AbortController();
        abortRef.current = controller;

        const list = [
            ...buildContext(messages),
            { role: "user", content: prompt },
        ];

        try {
            const response = await fetch("/chat", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    id: localStorage.getItem("userId"),
                    messages: list,
                    max_tokens: maxTokens,
                    temperature,
                    thinking,
                }),
                signal: controller.signal,
            });

            if (!response.ok) {
                throw new Error(`dd (${response.status})`);
            }

            const reader = response.body.getReader();
            const decoder = new TextDecoder();

            let buffer = "";
            let answer = "";
            let compactSummary = null;
            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true });

                // An event is complete only once its blank line has arrived
                let end;
                while ((end = buffer.indexOf("\n\n")) !== -1) {
                    const raw = buffer.slice(0, end);
                    buffer = buffer.slice(end + 2);

                    const [eventLine, dataLine] = raw.split("\n");
                    const event = eventLine.slice("event: ".length);
                    // compact is a bare flag: no data to parse
                    if (event === "compact") {
                        patch(replyId, () => ({ compacting: true }));
                        continue;
                    }

                    const data = JSON.parse(dataLine.slice("data: ".length));

                    if (event === "finished_compact") {
                        // Carries the summary; this reply becomes the new
                        // context base for later requests
                        compactSummary = data.text;
                        setSession((s) => ({
                            ...s,
                            compactions: s.compactions + 1,
                        }));
                        patch(replyId, () => ({
                            compacting: false,
                            summary: compactSummary,
                        }));
                    } else if (event === "reasoning") {
                        patch(replyId, (m) => ({
                            reasoning: m.reasoning + data.text,
                            thinkStart: m.thinkStart ?? Date.now(),
                        }));
                    } else if (event === "answer") {
                        answer += data.text;
                        patch(replyId, (m) => ({
                            text: m.text + data.text,
                            thinkEnd:
                                m.thinkStart && !m.thinkEnd
                                    ? Date.now()
                                    : m.thinkEnd,
                        }));
                    } else if (event === "done") {
                        // Raw payload, shown as-is under the message
                        patch(replyId, () => ({
                            done: dataLine.slice("data: ".length),
                        }));
                        setSession((s) => ({
                            ...s,
                            prompt: s.prompt + (data.prompt_tokens ?? 0),
                            completion:
                                s.completion + (data.completion_tokens ?? 0),
                            requests: s.requests + 1,
                        }));
                    }
                }
            }

            const tokenResponse = await fetch("/tokens", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    id: localStorage.getItem("userId"),
                    // After compaction, count the context the next request
                    // will actually send (same shape as buildContext)
                    messages: compactSummary
                        ? [
                              { role: "assistant", content: compactSummary },
                              { role: "user", content: prompt },
                              { role: "assistant", content: answer },
                          ]
                        : [...list, { role: "assistant", content: answer }],
                }),
                signal: controller.signal,
            });

            if (!tokenResponse.ok) {
                throw new Error(`dd (${tokenResponse.status})`);
            }

            // { normal, summary } token counts, appended after the stats
            const { normal, summary } = await tokenResponse.json();
            setContext(normal);
            const tokens = `tokens: normal ${normal}, summary ${summary}`;
            patch(replyId, (m) => ({
                done: m.done ? `${m.done}\n${tokens}` : tokens,
            }));
        } catch (err) {
            if (err.name === "AbortError") {
                patch(replyId, () => ({ stopped: true }));
            } else {
                patch(replyId, () => ({ error: err.message }));
            }
        } finally {
            patch(replyId, (m) => ({
                pending: false,
                compacting: false,
                thinkEnd: m.thinkStart && !m.thinkEnd ? Date.now() : m.thinkEnd,
            }));
            abortRef.current = null;
            setBusy(false);
            refreshUsage();
            inputRef.current?.focus();
        }
    }

    function onKeyDown(e) {
        if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
            e.preventDefault();
            send();
        }
    }

    function clear() {
        abortRef.current?.abort();
        setMessages([]);
        setContext(0);
        inputRef.current?.focus();
    }

    function logOut() {
        setUser(null);
        setSession(emptySession);
        setContext(0);
        setUsage(null);
    }

    return (
        <div className="chat">
            <header className="chat-header" inert={modalOpen}>
                <h1>Inference</h1>
                <div className="header-actions">
                    <button
                        className="ghost"
                        onClick={clear}
                        disabled={messages.length === 0}
                    >
                        Clear
                    </button>
                    {user ? (
                        <>
                            <button
                                className="ghost"
                                onClick={() => setUsageOpen(true)}
                            >
                                Usage
                            </button>
                            <span className="user">{user}</span>
                            <button className="ghost" onClick={logOut}>
                                Log out
                            </button>
                        </>
                    ) : (
                        <button
                            className="ghost"
                            onClick={() => setAuthOpen(true)}
                        >
                            Log in
                        </button>
                    )}
                </div>
            </header>

            {usageOpen && (
                <Usage
                    session={session}
                    context={context}
                    limit={contextLimit}
                    usage={usage}
                    error={usageError}
                    onClose={closeUsage}
                />
            )}

            {authOpen && (
                <Auth
                    onClose={() => setAuthOpen(false)}
                    onAuthed={(name) => {
                        setUser(name);
                        setAuthOpen(false);
                        setInit(false);
                    }}
                    init={init}
                />
            )}

            <main
                className="messages"
                ref={listRef}
                onScroll={onScroll}
                inert={modalOpen}
            >
                {messages.length === 0 ? (
                    <p className="empty">Send a prompt to get started.</p>
                ) : (
                    messages.map((m) =>
                        m.role === "user" ? (
                            <div key={m.id} className="row user">
                                <div className="bubble">{m.text}</div>
                            </div>
                        ) : (
                            <Fragment key={m.id}>
                                {m.summary && (
                                    <div className="summary-divider">
                                        Earlier messages summarised
                                    </div>
                                )}
                                <div className="row assistant">
                                    <AssistantMessage
                                        m={m}
                                        onToggleReasoning={(open) =>
                                            patch(m.id, () => ({
                                                reasoningOpen: open,
                                            }))
                                        }
                                    />
                                </div>
                            </Fragment>
                        ),
                    )
                )}
            </main>

            <footer className="composer" inert={modalOpen}>
                <div
                    className="composer-box"
                    onClick={(e) => {
                        // Clicking empty space in the box focuses the input
                        if (e.target === e.currentTarget)
                            inputRef.current?.focus();
                    }}
                >
                    <textarea
                        ref={inputRef}
                        rows={1}
                        autoFocus
                        placeholder="Ask anything"
                        value={input}
                        onChange={(e) => setInput(e.target.value)}
                        onKeyDown={onKeyDown}
                    />

                    <div className="composer-bar">
                        <div className="settings">
                            <button
                                type="button"
                                className={`chip ${thinking ? "on" : ""}`}
                                onClick={() => setThinking(!thinking)}
                                aria-pressed={thinking}
                            >
                                Thinking
                            </button>
                            <label className="chip">
                                Max tokens
                                <input
                                    type="number"
                                    min="1"
                                    value={maxTokens}
                                    style={{
                                        width: `${String(maxTokens).length}ch`,
                                    }}
                                    onChange={(e) =>
                                        setMaxTokens(
                                            Number(e.target.value) || 1,
                                        )
                                    }
                                />
                            </label>
                            <label className="chip">
                                Temp
                                <input
                                    type="number"
                                    min="0"
                                    max="2"
                                    step="0.1"
                                    value={temperature}
                                    style={{
                                        width: `${String(temperature).length}ch`,
                                    }}
                                    onChange={(e) =>
                                        setTemperature(
                                            Number(e.target.value) || 0,
                                        )
                                    }
                                />
                            </label>
                        </div>

                        <div className="composer-actions">
                            {user && (
                                <ContextRing
                                    context={context}
                                    limit={contextLimit}
                                    onClick={() => setUsageOpen(true)}
                                />
                            )}
                            {busy ? (
                                <button
                                    className="send"
                                    onClick={() => abortRef.current?.abort()}
                                    aria-label="Stop"
                                >
                                    <svg
                                        width="12"
                                        height="12"
                                        viewBox="0 0 12 12"
                                        aria-hidden="true"
                                    >
                                        <rect
                                            width="12"
                                            height="12"
                                            rx="2"
                                            fill="currentColor"
                                        />
                                    </svg>
                                </button>
                            ) : (
                                <button
                                    className="send"
                                    onClick={send}
                                    disabled={!input.trim()}
                                    aria-label="Send"
                                >
                                    <svg
                                        width="16"
                                        height="16"
                                        viewBox="0 0 16 16"
                                        aria-hidden="true"
                                    >
                                        <path
                                            d="M8 13V3M3.5 7.5 8 3l4.5 4.5"
                                            fill="none"
                                            stroke="currentColor"
                                            strokeWidth="2"
                                            strokeLinecap="round"
                                            strokeLinejoin="round"
                                        />
                                    </svg>
                                </button>
                            )}
                        </div>
                    </div>
                </div>
            </footer>
        </div>
    );
}

function AssistantMessage({ m, onToggleReasoning }) {
    const thinkingNow = m.pending && !!m.reasoning && !m.text;
    // Auto: expanded while thinking, collapsed once the answer starts
    const open = m.reasoningOpen ?? thinkingNow;

    let label = "Thinking";
    if (!thinkingNow && m.thinkStart && m.thinkEnd) {
        const secs = Math.max(
            1,
            Math.round((m.thinkEnd - m.thinkStart) / 1000),
        );
        label = `Thought for ${secs}s`;
    }

    return (
        <div className="assistant-msg">
            {m.reasoning && (
                <div className={`reasoning ${open ? "open" : ""}`}>
                    <button
                        className={`reasoning-toggle ${thinkingNow ? "live" : ""}`}
                        onClick={() => onToggleReasoning(!open)}
                        aria-expanded={open}
                    >
                        <span>{label}</span>
                        <svg
                            className="chevron"
                            width="12"
                            height="12"
                            viewBox="0 0 12 12"
                            aria-hidden="true"
                        >
                            <path
                                d="M4 2.5 7.5 6 4 9.5"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="1.5"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                            />
                        </svg>
                    </button>
                    {open && (
                        <div className="reasoning-body">{m.reasoning}</div>
                    )}
                </div>
            )}

            {m.compacting && (
                <div className="compacting" role="status">
                    <span className="spinner" aria-hidden="true" />
                    <span>Compacting conversation</span>
                </div>
            )}

            {m.pending && !m.compacting && !m.text && !m.reasoning && (
                <span className="dots" aria-label="Generating">
                    <i />
                    <i />
                    <i />
                </span>
            )}

            {m.text && (
                <div className="answer">
                    {m.reasoning && <div className="answer-label">Answer</div>}
                    <div className="md">
                        <Markdown
                            remarkPlugins={[remarkGfm, remarkMath]}
                            rehypePlugins={[rehypeKatex]}
                        >
                            {normalizeMath(m.text)}
                        </Markdown>
                    </div>
                </div>
            )}

            {m.done && <pre className="done">{m.done}</pre>}

            {m.error && <span className="note error">{m.error}</span>}
            {m.stopped && <span className="note">Stopped</span>}
        </div>
    );
}
