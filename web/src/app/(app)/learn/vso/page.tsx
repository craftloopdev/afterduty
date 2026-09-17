import { LearnArticle, LearnSection } from "@/components/education/LearnArticle";

// "Free help: VSOs" (P1-18) — what they are, that they're free, and how to
// find an accredited one. Static RSC; no outcome promises.

export const metadata = { title: "Free help: VSOs — After Duty" };

export default function VsoPage() {
  return (
    <LearnArticle
      title="Free help: VSOs"
      lede="Veterans Service Officers are trained, VA-accredited people who help you file claims — and their help is free. Not discounted. Free."
    >
      <LearnSection title="What a VSO is">
        <p>
          A VSO (Veterans Service Officer) works for a veterans service organization — like the
          DAV, VFW, or American Legion — or for your county&rsquo;s veteran service office. They
          are accredited by VA, which means VA has recognized them as qualified to prepare and
          file claims on your behalf.
        </p>
        <p>
          They can file your Intent to File, build and submit the claim, track it, and sit with
          you through the process. You do not need to be a member of their organization.
        </p>
      </LearnSection>

      <LearnSection title="They are free — be careful of anyone who isn't">
        <p>
          Accredited VSOs never charge to help you file a claim. If someone wants a fee — or a
          percentage of your back pay — to file an <b>initial</b> claim, walk away. (Accredited
          attorneys and claims agents may lawfully charge fees for <b>appeals</b>, but that&rsquo;s
          a different situation and the fees are regulated.)
        </p>
      </LearnSection>

      <LearnSection title="How to find one">
        <ul>
          <li>
            VA&rsquo;s guide:{" "}
            <a
              href="https://www.va.gov/get-help-from-accredited-representative/"
              target="_blank"
              rel="noopener noreferrer"
            >
              Get help from an accredited representative
            </a>{" "}
            on va.gov.
          </li>
          <li>
            Check anyone&rsquo;s accreditation in{" "}
            <a
              href="https://www.va.gov/ogc/apps/accreditation/index.asp"
              target="_blank"
              rel="noopener noreferrer"
            >
              VA&rsquo;s official accreditation search
            </a>
            .
          </li>
          <li>Search for your county or state &ldquo;veteran service office&rdquo; — most counties have one.</li>
          <li>Walk into a local DAV, VFW, or American Legion post and ask for their service officer.</li>
        </ul>
      </LearnSection>

      <LearnSection title="How this app and a VSO work together">
        <p>
          After Duty helps you organize your evidence and understand what VA looks for. A VSO
          can actually file for you. They work well together: show up to your VSO with your
          records organized and your gaps understood, and their job — and yours — gets easier.
        </p>
      </LearnSection>
    </LearnArticle>
  );
}
