import type { Metadata } from "next";
import type { ReactNode } from "react";

import "./globals.css";

export const metadata: Metadata = {
  title: "Электронное меню",
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="ru-RU">
      <body>
        <a className="skip-link" href="#main-content">
          Перейти к содержимому
        </a>
        <div id="main-content">{children}</div>
      </body>
    </html>
  );
}
