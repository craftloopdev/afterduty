import { LearnArticle, LearnSection } from "@/components/education/LearnArticle";
import { Term } from "@/components/ui/Term";

// "Intent to File — lock in your date" (P1-18) — the back-pay teaching.
// Static RSC; no data loads, no outcome promises.

export const metadata = { title: "Intent to File — After Duty" };

export default function IntentToFilePage() {
  return (
    <LearnArticle
      title="Intent to File — lock in your date"
      lede="One free, one-page form locks in your date before your claim is ready. If VA grants the claim later, back pay counts from that date — not from the day you finished the paperwork."
    >
      <LearnSection title="What an Intent to File is">
        <p>
          An <Term k="itf">Intent to File</Term> (VA Form 21-0966) tells VA &ldquo;a claim is
          coming.&rdquo; Filing it takes minutes and costs nothing. You then get up to one year
          to gather evidence and submit the full claim.
        </p>
        <p>
          It matters because of your <Term k="effective-date">effective date</Term> — the date VA
          starts owing you benefits. With an ITF on file, that date is the day VA received the
          ITF, not the day you finally submitted everything.
        </p>
      </LearnSection>

      <LearnSection title="Why the date is worth real money">
        <p>
          Say you file an ITF in January and spend until October getting your evidence together.
          If VA grants the claim, payments count from January — nine extra months of back pay,
          just because the one-page form went in first.
        </p>
        <p>
          Without an ITF, the clock starts when the finished claim arrives. Every month spent
          gathering records without one is a month of back pay you can&rsquo;t get back.
        </p>
      </LearnSection>

      <LearnSection title="How to file one (free, today)">
        <ul>
          <li>
            Online: start a claim application at{" "}
            <a href="https://www.va.gov/disability/how-to-file-claim/" target="_blank" rel="noopener noreferrer">
              va.gov
            </a>{" "}
            — starting the application sets your Intent to File automatically.
          </li>
          <li>By phone: call VA at 800-827-1000 and say you want to file an Intent to File.</li>
          <li>Through a VSO: a free accredited rep can file it for you in minutes.</li>
        </ul>
        <p>
          An ITF doesn&rsquo;t commit you to anything and doesn&rsquo;t guarantee approval — it
          just protects your date while you get ready.
        </p>
      </LearnSection>
    </LearnArticle>
  );
}
