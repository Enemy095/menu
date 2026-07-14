import {
  MenuResponseFromJSON,
  PublicConfigurationResponseFromJSON,
  type MenuResponse,
  type PublicConfigurationResponse,
} from "@e-menu/api-contract/generated";

import { rawConfiguration, rawMenu, restaurantId } from "./raw-public-data";

export { rawConfiguration, rawMenu, restaurantId };

export function configurationFixture(): PublicConfigurationResponse {
  return PublicConfigurationResponseFromJSON(structuredClone(rawConfiguration));
}

export function menuFixture(): MenuResponse {
  return MenuResponseFromJSON(structuredClone(rawMenu));
}
