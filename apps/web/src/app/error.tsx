"use client";

import { useEffect } from "react";

import { ServiceStatus } from "@/shared/ui/ServiceStatus";

export default function ErrorPage({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => {
    console.error("Public menu rendering failed", { digest: error.digest });
  }, [error.digest]);

  return (
    <div>
      <ServiceStatus
        title="Не удалось загрузить меню"
        message="Попробуйте обновить данные. Если ошибка повторится, вернитесь позже."
      />
      <div className="service-status__action">
        <button className="button" type="button" onClick={reset}>
          Повторить
        </button>
      </div>
    </div>
  );
}
