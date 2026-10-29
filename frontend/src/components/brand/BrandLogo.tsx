import seekerhutMark from "@/assets/brand/seekerhut-mark.png";
import { cn } from "@/lib/utils";

interface BrandLogoProps {
  compact?: boolean;
  className?: string;
}

export function BrandLogo({ compact = false, className }: BrandLogoProps) {
  return (
    <span className={cn(
      "inline-flex shrink-0 items-center whitespace-nowrap font-semibold normal-case text-[#31574a]",
      compact ? "text-[11px] tracking-[0.06em]" : "gap-2 text-lg tracking-tight",
      className,
    )}>
      {!compact && <img src={seekerhutMark} width={32} height={32} className="h-8 w-8 shrink-0" alt="" aria-hidden="true" />}
      <span>seekerhut</span>
    </span>
  );
}
