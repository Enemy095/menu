
# Branding


## Properties

Name | Type
------------ | -------------
`logoUrl` | string
`logoAlt` | string
`faviconUrl` | string
`hero` | [Hero](Hero.md)
`designTokens` | [DesignTokens](DesignTokens.md)
`localizedTexts` | [Array&lt;LocalizedRestaurantText&gt;](LocalizedRestaurantText.md)

## Example

```typescript
import type { Branding } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "logoUrl": null,
  "logoAlt": null,
  "faviconUrl": null,
  "hero": null,
  "designTokens": null,
  "localizedTexts": null,
} satisfies Branding

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Branding
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
