import type { NextConfig } from "next";
import path from "node:path";

const nextConfig: NextConfig = {
  ...(process.env.DOCKER_STANDALONE === "true"
    ? { output: "standalone", outputFileTracingRoot: path.join(__dirname, "../..") }
    : {}),
  // Never let `next build` replace files used by a running dev server.
  // This was the root cause of the broken LAN/ngrok session on 2026-07-17.
  distDir: process.env.NODE_ENV === "development" ? ".next-dev" : ".next",
  allowedDevOrigins: [
    "localhost",
    "127.0.0.1",
    "192.168.0.100",
    "2f42-81-99-233-163.ngrok-free.app",
  ],
  poweredByHeader: false,
  reactStrictMode: true,
  transpilePackages: ["@e-menu/api-contract"],
};

export default nextConfig;
