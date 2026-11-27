export function PageHeader({ title, description }: { title: string; description: string }) {
  return <header className="page-header"><h1>{title}</h1><p>{description}</p></header>;
}
