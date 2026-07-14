export default function Loading() {
  return (
    <main className="service-status" aria-busy="true" aria-live="polite">
      <div className="service-status__card service-status__card--loading">
        <span className="loading-line" />
        <span className="loading-line loading-line--wide" />
        <span className="loading-line" />
        <span className="sr-only">Загрузка меню</span>
      </div>
    </main>
  );
}
