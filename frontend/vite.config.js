import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// https://vite.dev/config/
export default defineConfig({
    plugins: [react()],
    server: {
        proxy: {
            "/chat": "http://100.115.30.63:8091",
            "/login": "http://100.115.30.63:8091",
            "/signup": "http://100.115.30.63:8091",
        },
    },
});
