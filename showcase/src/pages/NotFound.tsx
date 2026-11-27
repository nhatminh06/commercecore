import { PageHeader } from '../components/PageHeader';

export function NotFound() {
  return <><PageHeader title="Page not found" description="This address does not match a showcase page." /><p><a href="/">Return to overview</a></p></>;
}
