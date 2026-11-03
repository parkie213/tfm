/**
 * The route-colour key shape, shared by the map (which discovers the routes in view) and the DOM
 * `RouteLegend` (which renders the chips). Map-library-agnostic so neither side depends on the other:
 * `StationMapGL`/`BusRoutesGL` produce these, `App`/`RouteLegend` consume them.
 */
export interface RouteLegendItem {
  shortName: string
  longName: string
  color: string
  operator: string
}
