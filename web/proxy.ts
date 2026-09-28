import { NextResponse, type NextRequest } from "next/server";
import { isAllowedPublicApi } from "@/lib/public-api-policy";

export function proxy(request: NextRequest) {
  if (process.env.NEXT_PUBLIC_DEMO_MODE !== "public") return NextResponse.next();
  if (isAllowedPublicApi(request.method, request.nextUrl.pathname)) return NextResponse.next();
  return NextResponse.json({ code: "public_demo_route_blocked", message: "This API is not exposed by the public portfolio deployment." }, { status: 404 });
}

export const config = { matcher: ["/api/commercecore/:path*", "/api/payment-provider/:path*"] };
