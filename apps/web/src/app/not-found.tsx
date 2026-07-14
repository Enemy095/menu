import { ServiceStatus } from "@/shared/ui/ServiceStatus";

export default function NotFound() {
  return (
    <ServiceStatus
      title="Меню не найдено"
      message="Для этого адреса публичное меню не настроено."
    />
  );
}
