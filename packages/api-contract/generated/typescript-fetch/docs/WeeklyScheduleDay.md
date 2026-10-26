
# WeeklyScheduleDay


## Properties

Name | Type
------------ | -------------
`dayOfWeek` | [DayOfWeek](DayOfWeek.md)
`intervals` | [Array&lt;OpeningInterval&gt;](OpeningInterval.md)

## Example

```typescript
import type { WeeklyScheduleDay } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "dayOfWeek": null,
  "intervals": null,
} satisfies WeeklyScheduleDay

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as WeeklyScheduleDay
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
