import { PageHeader } from '../components/PageHeader';

export function Evidence() {
  return <>
    <PageHeader title="Evidence and reproduction" description="Results must retain source revision, provenance, assumptions, and qualification limits." />
    <section className="panel" aria-labelledby="page-status">
      <p className="eyebrow" id="page-status">Publication pending</p>
      <p>Public evidence selection and privacy review remain pending. Existing qualification documentation distinguishes local execution from hosted CI coverage and does not establish global correctness.</p>
      <ul><li><a href="https://github.com/nhatminh06/commercecore/blob/c17118e3db9250cd2d51476ecce05bbee97f4f42/docs/roadmap/M22-QUALIFICATION.md">M22 qualification and reproduction</a></li></ul>
    </section>
  </>;
}
