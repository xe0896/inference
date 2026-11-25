import { useEffect } from "react";

const fmt = (n) => (n == null ? "—" : Number(n).toLocaleString());

// Backend summarises once the context passes this share of the limit
export const COMPACT_AT = 0.6;
// Used until /usage provides context_length (matches backend CONTEXT_LIMIT)
export const DEFAULT_CONTEXT_LIMIT = 4096;

// Expected /usage response (all fields optional; missing ones show "—"):
// {
//   "prompt_tokens": number,      // all-time tokens read by the model
//   "completion_tokens": number,  // all-time tokens generated
//   "requests": number,           // all-time messages sent
//   "compaction": number,         // all-time summarisations
//   "context_length": number      // model context window
// }
export async function fetchUsage() {
    const response = await fetch("/usage", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ id: localStorage.getItem("userId") }),
    });
    if (!response.ok)
        throw new Error(`Couldn't load usage (${response.status})`);
    return response.json();
}

// Small ring in the composer showing how full the context is
export function ContextRing({ context, limit, onClick }) {
    const pct = Math.min(1, context / limit);
    const r = 7;
    const c = 2 * Math.PI * r;
    const label = `${Math.round(pct * 100)}% of context used`;

    return (
        <button
            type="button"
            className={`ring ${pct >= COMPACT_AT ? "warn" : ""}`}
            onClick={onClick}
            aria-label={`${label}. Open usage`}
            data-tip={`${label} · ${fmt(context)} / ${fmt(limit)} tokens`}
        >
            <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
                <circle className="ring-track" cx="9" cy="9" r={r} />
                <circle
                    className="ring-fill"
                    cx="9"
                    cy="9"
                    r={r}
                    strokeDasharray={c}
                    strokeDashoffset={c * (1 - pct)}
                />
            </svg>
        </button>
    );
}

export default function Usage({
    session,
    context,
    limit,
    usage,
    error,
    onClose,
}) {
    useEffect(() => {
        const onKey = (e) => e.key === "Escape" && onClose();
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, [onClose]);

    const pct = Math.min(1, context / limit);
    const left = Math.max(0, Math.round(limit * COMPACT_AT) - context);

    return (
        <div
            className="auth-backdrop"
            onMouseDown={(e) => e.target === e.currentTarget && onClose()}
        >
            <div
                className="usage"
                role="dialog"
                aria-modal="true"
                aria-labelledby="usage-title"
            >
                <div className="usage-head">
                    <h2 id="usage-title">Usage</h2>
                    <button
                        className="icon-btn"
                        onClick={onClose}
                        aria-label="Close"
                        autoFocus
                    >
                        <svg
                            width="14"
                            height="14"
                            viewBox="0 0 14 14"
                            aria-hidden="true"
                        >
                            <path
                                d="M3 3l8 8M11 3l-8 8"
                                stroke="currentColor"
                                strokeWidth="1.6"
                                strokeLinecap="round"
                            />
                        </svg>
                    </button>
                </div>

                <section className="usage-section">
                    <h3>This conversation</h3>
                    <div className="memory">
                        <div className="memory-top">
                            <span className="memory-pct">
                                {Math.round(pct * 100)}% used
                            </span>
                            <span>
                                {fmt(context)} / {fmt(limit)} tokens
                            </span>
                        </div>
                        <div
                            className={`meter ${pct >= COMPACT_AT ? "warn" : ""}`}
                            role="meter"
                            aria-valuemin={0}
                            aria-valuemax={limit}
                            aria-valuenow={context}
                            aria-label="Conversation memory used"
                        >
                            <i style={{ width: `${pct * 100}%` }} />
                            <b style={{ left: `${COMPACT_AT * 100}%` }} />
                        </div>
                        <p className="hint">
                            {left > 0
                                ? `About ${fmt(left)} tokens until older messages are summarised to free up space.`
                                : "Older messages will be summarised on your next message to free up space."}
                        </p>
                    </div>
                </section>

                <section className="usage-section">
                    <h3>This session</h3>
                    <Rows
                        rows={[
                            ["Messages sent", null, session.requests],
                            [
                                "Tokens generated",
                                "Replies, including thinking",
                                session.completion,
                            ],
                            [
                                "Tokens read",
                                "History is re-read with every message",
                                session.prompt,
                            ],
                            ["Times summarised", null, session.compactions],
                        ]}
                    />
                </section>

                <section className="usage-section">
                    <h3>All time</h3>
                    {error ? (
                        <p className="hint error">{error}</p>
                    ) : (
                        <Rows
                            loading={!usage}
                            rows={[
                                ["Messages sent", null, usage?.requests],
                                [
                                    "Tokens generated",
                                    null,
                                    usage?.completion_tokens,
                                ],
                                ["Tokens read", null, usage?.prompt_tokens],
                                ["Times summarised", null, usage?.compaction],
                            ]}
                        />
                    )}
                </section>
            </div>
        </div>
    );
}

function Rows({ rows, loading }) {
    return (
        <dl className="rows">
            {rows.map(([label, hint, value]) => (
                <div key={label} className="row-item">
                    <dt>
                        {label}
                        {hint && <span className="hint">{hint}</span>}
                    </dt>
                    <dd>{loading ? "…" : fmt(value)}</dd>
                </div>
            ))}
        </dl>
    );
}
