import type { NextConfig } from "next";

const commerceCoreApiUrl = (
  process.env.COMMERCECORE_API_URL ?? "http://localhost:8080"
).replace(/\/$/, "");
const paymentProviderApiUrl = (
  process.env.PAYMENT_PROVIDER_API_URL ?? "http://localhost:8091"
).replace(/\/$/, "");

const nextConfig: NextConfig = {
  agentRules: false,
  output: "standalone",
  async rewrites() {
    const rewrites = [
      {
        source: "/api/commercecore/:path*",
        destination: `${commerceCoreApiUrl}/api/:path*`,
      },
    ];
    if (process.env.NEXT_PUBLIC_DEMO_MODE !== "public") rewrites.push({
        source: "/api/payment-provider/:path*",
        destination: `${paymentProviderApiUrl}/api/:path*`,
    });
    return rewrites;
  },
};

export default nextConfig;
