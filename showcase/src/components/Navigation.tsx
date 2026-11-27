export const routes = [
  ['/', 'Overview'], ['/walkthrough', 'Walkthrough'], ['/architecture', 'Architecture'],
  ['/lab', 'Consistency Lab'], ['/lab/debugger', 'Debugger'], ['/evidence', 'Evidence'],
] as const;

export function Navigation() {
  const path = window.location.pathname.replace(/\/$/, '') || '/';
  return <nav aria-label="Primary navigation">{routes.map(([href, label]) =>
    <a key={href} href={href} aria-current={path === href ? 'page' : undefined}>{label}</a>,
  )}</nav>;
}
