type ServiceStatusProps = {
  title: string;
  message: string;
};

export function ServiceStatus({ title, message }: ServiceStatusProps) {
  return (
    <main className="service-status">
      <div role="alert" className="service-status__card">
        <p className="eyebrow">Электронное меню</p>
        <h1>{title}</h1>
        <p>{message}</p>
      </div>
    </main>
  );
}
