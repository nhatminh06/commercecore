type PageHeaderProps = {
  title: string;
  description: string;
};

export function PageHeader({ title, description }: PageHeaderProps) {
  return (
    <div className="border-b border-border pb-4">
      <h1 className="text-[26px] font-semibold leading-tight tracking-tight text-foreground">{title}</h1>
      <p className="mt-1.5 max-w-3xl text-[13px] leading-5 text-muted-foreground">{description}</p>
    </div>
  );
}
