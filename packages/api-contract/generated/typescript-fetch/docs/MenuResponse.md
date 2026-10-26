
# MenuResponse


## Properties

Name | Type
------------ | -------------
`restaurantId` | string
`version` | number
`publishedAt` | Date
`currency` | string
`categories` | [Array&lt;MenuCategory&gt;](MenuCategory.md)
`items` | [Array&lt;MenuItem&gt;](MenuItem.md)

## Example

```typescript
import type { MenuResponse } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "restaurantId": null,
  "version": null,
  "publishedAt": null,
  "currency": null,
  "categories": null,
  "items": null,
} satisfies MenuResponse

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MenuResponse
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
