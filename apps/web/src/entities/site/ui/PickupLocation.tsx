import type { PublicProfile } from "@e-menu/api-contract/generated";

export type PickupLocationData = Pick<PublicProfile, "address" | "location">;

export function PickupLocation({ profile }: { profile?: PickupLocationData }) {
  if (!profile?.address || !profile.location) return null;
  const point = `${profile.location.longitude},${profile.location.latitude}`;
  const parameters = new URLSearchParams({ ll: point, z: "17", pt: `${point},pm2rdm`, lang: "ru_RU" });
  const mapUrl = `https://yandex.ru/maps/?${new URLSearchParams({ ll: point, z: "17", pt: point })}`;
  return (
    <div className="pickup-location">
      <strong>Адрес самовывоза</strong>
      <a className="pickup-location__address" href={mapUrl} target="_blank" rel="noopener noreferrer">{profile.address}</a>
      <div className="pickup-location__preview">
        <iframe title={`Карта: ${profile.address}`} src={`https://yandex.ru/map-widget/v1/?${parameters}`}
          loading="lazy" tabIndex={-1} aria-hidden="true" sandbox="allow-scripts allow-same-origin" />
        <a className="pickup-location__open" href={mapUrl} target="_blank" rel="noopener noreferrer" aria-label={`Открыть адрес самовывоза в Яндекс Картах: ${profile.address}`}>
          <span>Открыть в Яндекс Картах ↗</span>
        </a>
      </div>
    </div>
  );
}
