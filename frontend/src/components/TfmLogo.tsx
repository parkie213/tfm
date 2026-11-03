type Props = {
  className?: string;
  size?: number;
  /** Stroke + bar color. Defaults to currentColor so it inherits from text-* utilities. */
  color?: string;
  /** Number of crimps around the back of the pie's top crust. */
  crimps?: number;
  /** Amplitude of each crimp in viewBox units. */
  amplitude?: number;
  strokeWidth?: number;
  title?: string;
};

/**
 * Transport for Melton roundel.
 * 3/4 view pork pie silhouette: a shallow top disc with a crimped/fluted rim,
 * cylindrical sides, and a rounded base — with a TfL-style horizontal bar across.
 */
export function TfmLogo({
  className,
  size = 48,
  color = "currentColor",
  crimps = 14,
  amplitude = 3,
  strokeWidth = 11,
  title = "Transport for Melton",
}: Props) {
  // Top disc ellipse (the lid of the pie, seen from above-diagonal)
  const cx = 50;
  const topCy = 28;
  const rx = 32;
  const topRy = 9;

  // Base ellipse (front-facing arc of the pie's bottom)
  const baseCy = 74;
  const baseRy = 7;

  // --- Crimped rim helper: trace an arc of the top ellipse with a scallop wave ---
  // tStart..tEnd in radians; full ellipse = 0..2π. Wave count scales with arc length so
  // crimps stay the same size whether we draw the back half, front half, or full loop.
  const buildCrimped = (tStart: number, tEnd: number, startCmd: "M" | "L") => {
    const span = tEnd - tStart;
    const fraction = span / (Math.PI * 2);
    const waves = Math.max(1, Math.round(crimps * fraction));
    const steps = Math.max(20, waves * 10);
    const cmds: string[] = [];
    for (let i = 0; i <= steps; i++) {
      const u = i / steps;
      const t = tStart + u * span;
      let nx = Math.cos(t) / rx;
      let ny = Math.sin(t) / topRy;
      const nLen = Math.hypot(nx, ny);
      nx /= nLen;
      ny /= nLen;
      const bump = amplitude * (0.5 - 0.5 * Math.cos(waves * Math.PI * 2 * u));
      const x = cx + rx * Math.cos(t) + nx * bump;
      const y = topCy + topRy * Math.sin(t) + ny * bump;
      cmds.push(`${i === 0 ? startCmd : "L"}${x.toFixed(2)} ${y.toFixed(2)}`);
    }
    return cmds.join(" ");
  };

  // Outer silhouette: crimped back rim (π → 2π) → right side down → front base arc → left side up → close
  const silhouette =
    buildCrimped(Math.PI, Math.PI * 2, "M") +
    ` L${cx + rx} ${baseCy}` +
    ` A${rx} ${baseRy} 0 0 1 ${cx - rx} ${baseCy}` +
    ` L${cx - rx} ${topCy}` +
    ` Z`;

  // Front rim of the top disc — also crimped, so the fluting goes all the way around
  const frontRim = buildCrimped(0, Math.PI, "M");

  // Bar across the middle of the pie body
  const barY = (topCy + baseCy) / 2 + 2;
  const barHalfLen = rx + 10;
  const barThickness = 12;

  return (
    <svg
      viewBox="0 0 100 100"
      width={size}
      height={size}
      className={className}
      role="img"
      aria-label={title}
    >
      <title>{title}</title>
      <g
        fill="none"
        stroke={color}
        strokeWidth={strokeWidth}
        strokeLinejoin="round"
        strokeLinecap="round"
      >
        <path d={silhouette} />
        <path d={frontRim} strokeWidth={strokeWidth * 0.8} />
      </g>
      <rect
        x={cx - barHalfLen}
        y={barY - barThickness / 2}
        width={barHalfLen * 2}
        height={barThickness}
        fill={color}
        rx={1.5}
      />
    </svg>
  );
}
