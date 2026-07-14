
# Hero


## Properties

Name | Type
------------ | -------------
`imageUrl` | string
`imageAlt` | string
`eyebrow` | string
`title` | string
`subtitle` | string
`ctaLabel` | string
`benefits` | [Array&lt;HeroBenefit&gt;](HeroBenefit.md)

## Example

```typescript
import type { Hero } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "imageUrl": null,
  "imageAlt": null,
  "eyebrow": null,
  "title": null,
  "subtitle": null,
  "ctaLabel": null,
  "benefits": null,
} satisfies Hero

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Hero
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
