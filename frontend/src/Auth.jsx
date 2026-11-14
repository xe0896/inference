import { useEffect, useState } from "react";

// Backend contract (change here to match your server):
//   POST /login  or  POST /signup   body: { username, password }
//   2xx = success; anything else = failure, optional JSON { error: "..." }
async function submitAuth(mode, username, password) {
    const response = await fetch(mode === "login" ? "/login" : "/signup", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password }),
    });

    if (!response.ok) {
        let message;
        switch (response.status) {
            case 404:
                message = "Account not found with that username";
                break;
            case 401:
                message = "Incorrect password";
                break;
            case 409:
                message = "Account already exists with that username";
                break;
        }
        try {
            message = (await response.json()).error || message;
        } catch {
            // Non-JSON error body: keep the status message
        }
        throw new Error(message);
    } else {
        const bytes = new Uint8Array(await response.arrayBuffer());
        const id = new TextDecoder("utf-8").decode(bytes);

        localStorage.setItem("userId", id);
    }
}

export default function Auth({ onClose, onAuthed, init }) {
    const [mode, setMode] = useState("login");
    const [username, setUsername] = useState("");
    const [password, setPassword] = useState("");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");

    useEffect(() => {
        if (!init) {
            const onKey = (e) => e.key === "Escape" && onClose();
            window.addEventListener("keydown", onKey);
            return () => window.removeEventListener("keydown", onKey);
        }
    }, [onClose, init]);

    async function onSubmit(e) {
        e.preventDefault();
        if (busy) return;
        setBusy(true);
        setError("");
        try {
            await submitAuth(mode, username.trim(), password);
            onAuthed(username.trim());
        } catch (err) {
            setError(err.message);
        } finally {
            setBusy(false);
        }
    }

    const isLogin = mode === "login";

    return (
        <div
            className="auth-backdrop"
            onMouseDown={(e) =>
                !init && e.target === e.currentTarget && onClose()
            }
        >
            <form
                className="auth"
                onSubmit={onSubmit}
                role="dialog"
                aria-modal="true"
                aria-label={isLogin ? "Log in" : "Sign up"}
            >
                <h2>{isLogin ? "Welcome back" : "Create an account"}</h2>

                <input
                    type="text"
                    placeholder="Username"
                    autoComplete="username"
                    autoFocus
                    required
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                />
                <input
                    type="password"
                    placeholder="Password"
                    autoComplete={isLogin ? "current-password" : "new-password"}
                    required
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                />

                {error && <p className="auth-error">{error}</p>}

                <button className="auth-submit" disabled={busy}>
                    {isLogin ? "Log in" : "Sign up"}
                </button>

                <p className="auth-switch">
                    {isLogin ? "No account?" : "Already have an account?"}{" "}
                    <button
                        type="button"
                        onClick={() => {
                            setMode(isLogin ? "signup" : "login");
                            setError("");
                        }}
                    >
                        {isLogin ? "Sign up" : "Log in"}
                    </button>
                </p>
            </form>
        </div>
    );
}
