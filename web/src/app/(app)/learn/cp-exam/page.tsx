import { LearnArticle, LearnSection } from "@/components/education/LearnArticle";
import { Term } from "@/components/ui/Term";

// "Getting ready for your C&P exam" (P1-18) — what happens, what to bring,
// be honest about your worst days. Static RSC; no outcome promises.

export const metadata = { title: "Getting ready for your C&P exam — After Duty" };

export default function CpExamPage() {
  return (
    <LearnArticle
      title="Getting ready for your C&P exam"
      lede="After you file, VA usually schedules a Compensation and Pension exam. It's shorter and simpler than most veterans expect — and what you say there carries real weight."
    >
      <LearnSection title="What a C&P exam is">
        <p>
          A <Term k="cp-exam">C&amp;P exam</Term> is VA&rsquo;s own medical check for your claim.
          VA schedules it and pays for it — you never book or pay for one yourself. The examiner
          may be at a VA clinic or a VA contractor&rsquo;s office.
        </p>
        <p>
          It is not regular treatment. The examiner&rsquo;s job is to document your condition for
          the rating decision: they review your file, ask questions, and may do a brief physical
          check. Many exams take under 30 minutes.
        </p>
      </LearnSection>

      <LearnSection title="What to bring">
        <ul>
          <li>A photo ID.</li>
          <li>A list of the medicines you take for the condition.</li>
          <li>
            A few written notes about your symptoms — how often, how bad, and what they stop you
            from doing. It&rsquo;s easy to forget things in the moment.
          </li>
          <li>If someone sees your bad days up close, it&rsquo;s OK to bring them along.</li>
        </ul>
      </LearnSection>

      <LearnSection title="Be honest about your worst days">
        <p>
          This is the part veterans get wrong most. Many of us are trained to say &ldquo;I&rsquo;m
          fine&rdquo; — but if the examiner only hears about a good day, the report describes a
          good day, and the rating follows the report.
        </p>
        <ul>
          <li>Describe your <b>worst days</b>, and say how often they happen.</li>
          <li>Say what you can&rsquo;t do anymore — lifting, sleeping, concentrating, working.</li>
          <li>Don&rsquo;t exaggerate. Ever. Just don&rsquo;t minimize either.</li>
          <li>If it hurts during the physical check, say so — don&rsquo;t push through quietly.</li>
        </ul>
      </LearnSection>

      <LearnSection title="Practical things that protect your claim">
        <ul>
          <li>
            Don&rsquo;t miss the appointment — a no-show can stall or sink the claim. If you must
            reschedule, call the number on the letter right away.
          </li>
          <li>Arrive early and expect a wait.</li>
          <li>
            Afterward, you can request a copy of the exam report from VA to check it for errors.
          </li>
        </ul>
      </LearnSection>
    </LearnArticle>
  );
}
