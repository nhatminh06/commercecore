export function SectionHeader({ title, meta }: { title: string; meta?: string }) {
  return (
    <div className="mb-2.5 flex items-center justify-between border-b border-border pb-2">
      <h2 className="text-[10px] font-semibold uppercase tracking-[0.16em] text-muted-foreground">{title}</h2>
      {meta && <span className="text-[11px] text-muted-foreground">{meta}</span>}
    </div>
  );
}
