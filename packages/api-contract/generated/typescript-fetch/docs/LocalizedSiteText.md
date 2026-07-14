
# LocalizedSiteText


## Properties

Name | Type
------------ | -------------
`locale` | string
`displayName` | string
`shortName` | string
`businessTypeLabel` | string
`cuisineText` | string
`slogan` | string
`introText` | string
`menuSectionTitle` | string
`cartTitle` | string
`orderTypeLegend` | string

## Example

```typescript
import type { LocalizedSiteText } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "locale": null,
  "displayName": null,
  "shortName": null,
  "businessTypeLabel": null,
  "cuisineText": null,
  "slogan": null,
  "introText": null,
  "menuSectionTitle": null,
  "cartTitle": null,
  "orderTypeLegend": null,
} satisfies LocalizedSiteText

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as LocalizedSiteText
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
