import type { Metadata } from "next";

import { AppHeader } from "@/components/app-header";
import { AppSidebar } from "@/components/app-sidebar";

import "./globals.css";

export const metadata: Metadata = {
  title: { default: "CommerceCore Control Room", template: "%s | CommerceCore" },
  description: "Engineering interface for inspecting CommerceCore correctness behavior.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" className="dark">
      <body className="font-sans antialiased">
        <div className="min-h-screen lg:grid lg:grid-cols-[232px_minmax(0,1fr)]">
          <AppSidebar />
          <div className="min-w-0 lg:col-start-2">
            <AppHeader />
            <main className="mx-auto w-full max-w-[1500px] px-4 py-5 sm:px-6 lg:px-8 lg:py-7">{children}</main>
          </div>
        </div>
      </body>
    </html>
  );
}
