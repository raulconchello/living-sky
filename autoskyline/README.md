# autoskyline

Draws a skyline for any place without a hand-drawn one, from OpenStreetMap, Wikidata and terrain data.
Same SVG format as the hand-drawn skylines ([docs/SKYLINES.md](../docs/SKYLINES.md)). Results are cached per ~1 km.

```java
AutoSkyline.Result r = AutoSkyline.generate(lat, lon, "Place name", cacheDir); // r.daySvg, r.nightSvg
```

Plain Java 11. The app calls it by reflection, so if it breaks the widget falls back to a generic town.
