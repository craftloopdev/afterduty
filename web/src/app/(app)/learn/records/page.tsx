import { LearnArticle, LearnSection } from "@/components/education/LearnArticle";
import { Term } from "@/components/ui/Term";

// "Gathering your records" (P1-18) — DD-214, STRs, private records, buddy
// statements: what each is and exactly how to get it. Static RSC.

export const metadata = { title: "Gathering your records — After Duty" };

export default function RecordsPage() {
  return (
    <LearnArticle
      title="Gathering your records"
      lede="Claims are won on paper. Here's what each record is, why VA cares, and exactly how to get it — most of them free."
    >
      <LearnSection title="Your DD-214">
        <p>
          The discharge paper that proves when and where you served. It anchors the{" "}
          <Term k="in-service">in-service</Term> leg of nearly every claim, and for{" "}
          <Term k="presumptive">presumptive</Term> conditions it&rsquo;s often the main thing VA
          needs.
        </p>
        <ul>
          <li>Check wherever you keep important papers — and ask family.</li>
          <li>
            Lost? Request it free online through{" "}
            <a href="https://milconnect.dmdc.osd.mil/" target="_blank" rel="noopener noreferrer">
              milConnect
            </a>
            , or mail form <b>SF-180</b> to the National Personnel Records Center (
            <a href="https://www.archives.gov/veterans" target="_blank" rel="noopener noreferrer">
              archives.gov/veterans
            </a>
            ).
          </li>
          <li>It usually takes a few weeks; older records can take longer.</li>
        </ul>
      </LearnSection>

      <LearnSection title="Service treatment records (STRs)">
        <p>
          The medical file the military kept on you — sick call visits, injuries, complaints.
          This is where an in-service event usually lives.
        </p>
        <ul>
          <li>Request them the same ways as the DD-214: milConnect online, or SF-180 by mail.</li>
          <li>Ask for the <b>complete</b> medical and dental file, not a summary.</li>
          <li>They&rsquo;re free.</li>
        </ul>
      </LearnSection>

      <LearnSection title="VA medical records">
        <ul>
          <li>
            Download them yourself with the Blue Button on{" "}
            <a
              href="https://www.myhealth.va.gov/"
              target="_blank"
              rel="noopener noreferrer"
            >
              My HealtheVet
            </a>
            . Same day, free.
          </li>
          <li>Or ask the Release of Information office at your VA medical center.</li>
        </ul>
      </LearnSection>

      <LearnSection title="Private (civilian) medical records">
        <p>
          Records from civilian doctors carry the <Term k="diagnosis">diagnosis</Term> and show
          how serious the condition is now. You have a legal right to copies of your own records.
        </p>
        <ul>
          <li>
            Fastest: your patient portal (MyChart and similar) — download visit notes, test
            results, and imaging reports as PDFs.
          </li>
          <li>Otherwise: call the clinic&rsquo;s medical-records (or &ldquo;health information&rdquo;) office and ask for your records.</li>
          <li>Portals are free; paper copies can carry a small copying fee.</li>
        </ul>
      </LearnSection>

      <LearnSection title="Buddy statements">
        <p>
          Written statements from people who saw what happened in service — or who see how the
          condition affects you now. They can carry real weight when service records are thin.
        </p>
        <ul>
          <li>Ask a battle buddy, spouse, family member, or coworker who actually saw it.</li>
          <li>They should write plainly: what they saw, when, and how often.</li>
          <li>
            Use <b>VA Form 21-10210</b> (Lay/Witness Statement) — they sign it, you upload it.
            Free.
          </li>
        </ul>
      </LearnSection>
    </LearnArticle>
  );
}
