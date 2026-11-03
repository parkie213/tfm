// Smooths a polyline into a flowing curve for display. We use a centripetal Catmull-Rom spline,
// which (unlike Chaikin corner-cutting) PASSES THROUGH every input vertex and interpolates curvature
// *between* them — so a sweeping bend that OSRM/ST_Simplify left as just a few sparse points still
// renders as a smooth arc instead of visible facets. Centripetal parameterisation (alpha = 0.5)
// is the variant that avoids the loops/cusps a uniform spline produces at tight turns, which matters
// because the parallel-lane offset can bunch vertices together at junctions.
type Pt = [number, number]

function lerp(a: Pt, b: Pt, u: number): Pt {
  return [a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u]
}

function knot(t: number, a: Pt, b: Pt): number {
  // Centripetal: next knot = t + |b - a|^0.5. Clamp to a tiny epsilon so coincident points
  // (zero spacing) never divide by zero in the Barry–Goldman evaluation below.
  return t + Math.max(Math.sqrt(Math.hypot(b[0] - a[0], b[1] - a[1])), 1e-6)
}

/**
 * Returns a smoothed copy of `points`. `steps` is the number of interpolated points inserted per
 * input segment — higher is smoother but heavier (the whole route network is redrawn on every zoom).
 * Paths with < 3 points are returned unchanged.
 */
export function smoothPath(points: Pt[], steps = 6): Pt[] {
  const n = points.length
  if (n < 3 || steps < 2) return points

  const out: Pt[] = [points[0]]
  for (let i = 0; i < n - 1; i++) {
    // The four control points of this segment; ends are duplicated so the first/last segments curve.
    const p0 = points[i - 1] ?? points[i]
    const p1 = points[i]
    const p2 = points[i + 1]
    const p3 = points[i + 2] ?? points[i + 1]

    const t0 = 0
    const t1 = knot(t0, p0, p1)
    const t2 = knot(t1, p1, p2)
    const t3 = knot(t2, p2, p3)

    // Barry–Goldman pyramidal evaluation of the Catmull-Rom segment between p1 and p2.
    for (let s = 1; s <= steps; s++) {
      const t = t1 + ((t2 - t1) * s) / steps
      const a1 = lerp(p0, p1, (t - t0) / (t1 - t0))
      const a2 = lerp(p1, p2, (t - t1) / (t2 - t1))
      const a3 = lerp(p2, p3, (t - t2) / (t3 - t2))
      const b1 = lerp(a1, a2, (t - t0) / (t2 - t0))
      const b2 = lerp(a2, a3, (t - t1) / (t3 - t1))
      out.push(lerp(b1, b2, (t - t1) / (t2 - t1)))
    }
  }
  return out
}
