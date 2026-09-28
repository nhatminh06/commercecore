import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

type StatusBadgeProps = {
  children: React.ReactNode;
  tone?: "neutral" | "success" | "warning" | "danger";
};

const tones = {
  neutral: "",
  success: "border-emerald-900 bg-emerald-950 text-emerald-300",
  warning: "border-amber-900 bg-amber-950 text-amber-300",
  danger: "border-red-900 bg-red-950 text-red-300",
};

export function StatusBadge({ children, tone = "neutral" }: StatusBadgeProps) {
  return <Badge className={cn(tones[tone])}>{children}</Badge>;
}
