// Train station CRS code for Melton Mowbray
export const MELTON_MOWBRAY_CRS = 'MMO'

// Geographic position of Melton Mowbray railway station
// (OSM stop_position node 4312291931 / Wikidata — the actual station, not the town centre).
export const MELTON_STATION = {
  lat: 52.76099,
  lng: -0.88629,
  crs: 'MMO',
  name: 'Melton Mowbray',
} as const

// Melton Mowbray town centre (Market Place). The TV view treats all bus stops within
// TOWN_CENTRE_RADIUS_M of this point as a single logical "town centre" stop. The point also
// matches the backend's stop-search proximity bias, so the two stay consistent.
export const MELTON_TOWN_CENTRE = {
  lat: 52.7661,
  lng: -0.8856,
  name: 'Melton Mowbray town centre',
} as const

// How far out from the town centre a bus stop still counts as "town centre" (metres).
export const TOWN_CENTRE_RADIUS_M = 300
