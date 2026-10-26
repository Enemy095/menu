
# PublicAvailability


## Properties

Name | Type
------------ | -------------
`businessDate` | Date
`timezone` | string
`closedToday` | boolean
`nextOpenDate` | Date
`weeklyClosedDays` | Set&lt;number&gt;
`closedDates` | Set&lt;Date&gt;

## Example

```typescript
import type { PublicAvailability } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "businessDate": null,
  "timezone": null,
  "closedToday": null,
  "nextOpenDate": null,
  "weeklyClosedDays": null,
  "closedDates": null,
} satisfies PublicAvailability

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as PublicAvailability
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
