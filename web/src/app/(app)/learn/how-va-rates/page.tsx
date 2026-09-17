import { LearnArticle, LearnSection } from "@/components/education/LearnArticle";
import { Term } from "@/components/ui/Term";

// "How VA rates disabilities" (P1-18) — the triad explained in plain words.
// Static RSC; no data loads, no outcome promises.

export const metadata = { title: "How VA rates disabilities — After Duty" };

export default function HowVaRatesPage() {
  return (
    <LearnArticle
      title="How VA rates disabilities"
      lede="VA doesn't rate how tough you are. It rates what your paperwork proves. Here are the three things every claim needs — and how the percentage gets set."
    >
      <LearnSection title="The three legs of every claim">
        <p>For VA to connect a condition to your service, your evidence has to show three things:</p>
        <ol>
          <li>
            <b>A current <Term k="diagnosis">diagnosis</Term>.</b> A medical provider has named
            the condition in your records. &ldquo;My knee hurts&rdquo; isn&rsquo;t enough on its
            own — VA wants it in writing from a provider.
          </li>
          <li>
            <b>Something that happened <Term k="in-service">in service</Term>.</b> An injury,
            illness, exposure, or event that shows up in your service records — or in statements
            from people who were there.
          </li>
          <li>
            <b>A <Term k="nexus">nexus</Term>.</b> A medical opinion linking the two: your
            condition today traces back to what happened in service.
          </li>
        </ol>
        <p>
          If one leg is missing, VA usually says no — even when the condition is real. That&rsquo;s
          why this app shows the strength of each leg for every condition.
        </p>
      </LearnSection>

      <LearnSection title="How the percentage gets set">
        <p>
          VA uses a rulebook called the <Term k="vasrd">VASRD</Term>. Every condition has a{" "}
          <Term k="dc-code">diagnostic code</Term> that lists exactly what evidence earns each
          percentage. The number comes from <Term k="severity">severity</Term> — how bad the
          condition is today, as shown in your records.
        </p>
        <p>
          Two more things surprise most veterans: VA combines ratings with its own math, so two
          50% ratings do not add up to 100%. And a rule called{" "}
          <Term k="pyramiding">pyramiding</Term> stops VA from paying twice for the same symptom
          under two different names.
        </p>
      </LearnSection>

      <LearnSection title="Pyramiding — why some conditions don't add" id="pyramiding">
        <p>
          <Term k="pyramiding">Pyramiding</Term> is a VA rule (38 CFR &sect;4.14) that stops the
          same symptom from being paid twice under two different names. When two conditions describe
          the same underlying impairment &mdash; say depression and anxiety both rated under the
          mental-health formula &mdash; VA rates them <b>together</b> under one evaluation and counts
          only the strongest. They don&rsquo;t stack.
        </p>
        <p>
          That&rsquo;s why your breakdown may fold several conditions into one line (for example
          &ldquo;Mental health &mdash; 70%&rdquo;) and note the others as &ldquo;rated together, they
          don&rsquo;t add.&rdquo; It isn&rsquo;t losing you anything &mdash; it&rsquo;s how VA was
          always going to score it. A separate condition that affects a <i>different</i> part of the
          body (say a knee) is <b>not</b> pyramiding, so it still combines with VA math.
        </p>
      </LearnSection>

      <LearnSection title="The presumptive shortcut">
        <p>
          For some conditions the law already presumes the service connection — these are called{" "}
          <Term k="presumptive">presumptive</Term> conditions. Certain burn pit illnesses under
          the PACT Act and Agent Orange conditions are examples. If a presumption covers you,
          you usually do <b>not</b> need a nexus letter — proof of where and when you served is
          typically enough.
        </p>
      </LearnSection>
    </LearnArticle>
  );
}
