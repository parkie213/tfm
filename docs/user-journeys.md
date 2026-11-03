# User journeys

Use these for manual smoke checks after changing the UI.

1. Open the map and search for Melton Mowbray. Select a bus stop, inspect departures,
   open a service, and click a calling point to focus it on the map.
2. Select a bus route on the map or legend. Confirm its detail panel and route highlight
   agree. Clear the selection and check that the map remains usable.
3. With a BODS key configured, select a live bus. Inspect calling points and running
   estimates, follow it, then pan the map to release camera follow.
4. With an RDM LDBWS key configured, select Melton Mowbray railway station. Inspect the
   train board and coach details. With optional timetable data, open a train's calling points.
5. Open `?view=tv`, choose a Melton address, and check the kiosk map and train/bus boards.
   Exit to return to the main map. Check the layout at a desktop and mobile width.
6. Open the data-sources page. Missing credentials should show an unavailable source,
   rather than claiming that its data is live.
7. Deny location permission and confirm the “near me” control stops loading. Allow it
   and confirm that nearby stops can be selected.

Live-feed checks depend on configured subscriptions and current upstream coverage.
