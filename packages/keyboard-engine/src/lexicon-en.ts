/**
 * Tiny English development lexicon (ordered by approximate frequency). Production ships a native
 * n-gram model per locale as a downloadable asset (SPEC §12.4); this is NOT that.
 */
import type { Lexicon } from './predictor';

const WORDS = `the be to of and a in that have i it for not on with he as you do at this but his by from they we
say her she or an will my one all would there their what so up out if about who get which go me when make can
like time no just him know take people into year your good some could them see other than then now look only come
its over think also back after use two how our work first well way even new want because any these give day most
us is are was were been has had did does said thanks thank please hello hi hey yes okay ok sure great love today
tomorrow tonight morning evening week weekend meeting call later soon sorry really very much more here where why
should need let help available price prices order delivery deliver shipping discount offer product products buy
sell send sent sending message reply interested interest happy glad hope best regards kind dear team customer client
business service services quality free cost costs pay payment account email phone number address name company
office home schedule appointment confirm confirmed booking book ready done right left still again never always
every many each those such own same another while through during before between under around without within along
following across behind beyond plus except however therefore although though since until unless whether either
neither both few several enough quite rather almost already also again yet once twice question answer problem
issue solution idea plan project report review update details information document file share check know understand
explain describe summarize write writing read reading speak listen learn study school class student teacher essay
paper research environmental environment crisis climate change global warming pollution planet sustainable future
nature natural energy water air earth species biodiversity human humanity growth development industrial material
modern urgent immediate action collective demands demand attention exigent pernicious multifaceted elucidate imperative
consequence consequences dimension dimensions essay seeks aims purpose important critical pressing major minor
able about above accept according actually add age ago agree ahead allow amazing amount and anyone anything anyway
apply area arrive ask away awesome bad bag bank base beautiful become bed begin believe better big bit black blue
body bring brother build busy car care case cause chance cheap child children city clear close cold color coming
community complete consider continue control cool country couple course cover create cut dad date decide deep definitely
different difficult dinner direct discuss doctor dog door down drive early easy eat else end enjoy especially event
everyone everything exactly example excited expect experience face fact family far fast feel feeling find fine finish
fix food friend friends front full fun game girl going gone got group guess guy hand hard head hear heart high hold
hour house huge important job keep kid kids lady large last late least leave less life light line little live long
lot low main man matter maybe mean meet member mind minute miss mom money month move movie music must nice night note
open part party past person picture place point possible post power pretty probably pull put quick quickly real reason
remember rest return room run safe save second seem set short show side simple sister sit small someone something
sometimes song start stay stop story sure talk tell thing things thought together told top travel true try turn type
until usually wait walk watch water wish woman wonderful word world worry wrong yeah yesterday young`;

/** Common continuations; "<s>" = sentence start. */
const BIGRAMS: ReadonlyArray<[string, string[]]> = [
  ['<s>', ['i', 'thanks', 'hi']],
  ['i', ['am', 'will', 'think']],
  ['thank', ['you', 'for', 'so']],
  ['thanks', ['for', 'so', 'again']],
  ['how', ['are', 'much', 'about']],
  ['are', ['you', 'we', 'there']],
  ['you', ['for', 'are', 'can']],
  ['can', ['you', 'i', 'we']],
  ['will', ['be', 'send', 'call']],
  ['let', ['me', 'us', 'you']],
  ['me', ['know', 'a', 'the']],
  ['the', ['price', 'best', 'same']],
  ['is', ['the', 'available', 'it']],
  ['it', ['is', 'was', 'will']],
  ['see', ['you', 'the', 'it']],
  ['looking', ['forward', 'for', 'at']],
  ['for', ['the', 'your', 'you']],
  ['have', ['a', 'the', 'you']],
  ['a', ['great', 'good', 'few']],
  ['good', ['morning', 'luck', 'day']],
  ['great', ['day', 'idea', 'job']],
  ['what', ['is', 'do', 'time']],
  ['in', ['the', 'a', 'stock']],
  ['on', ['the', 'my', 'it']],
];

export function englishDevLexicon(): Lexicon {
  const list = WORDS.split(/\s+/).filter(Boolean);
  const unigrams = new Map<string, number>();
  list.forEach((w, rank) => {
    if (!unigrams.has(w)) unigrams.set(w, Math.max(1, list.length - rank));
  });
  // Extra words used by bigrams that may be absent from the list.
  for (const w of ['am', 'forward', 'stock', 'luck', 'job']) if (!unigrams.has(w)) unigrams.set(w, 50);
  return { unigrams, bigrams: new Map(BIGRAMS) };
}
