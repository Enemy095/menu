
# DesignTokens


## Properties

Name | Type
------------ | -------------
`primaryColor` | string
`secondaryColor` | string
`accentColor` | string
`backgroundColor` | string
`surfaceColor` | string
`textColor` | string
`mutedTextColor` | string
`borderRadius` | string
`fontFamily` | string

## Example

```typescript
import type { DesignTokens } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "primaryColor": #A33A2B,
  "secondaryColor": #A33A2B,
  "accentColor": #A33A2B,
  "backgroundColor": #A33A2B,
  "surfaceColor": #A33A2B,
  "textColor": #A33A2B,
  "mutedTextColor": #A33A2B,
  "borderRadius": 1rem,
  "fontFamily": null,
} satisfies DesignTokens

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DesignTokens
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
