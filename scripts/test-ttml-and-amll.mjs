import assert from 'node:assert';
import { parseTtml, parseTtmlTime, decodeXmlEntities } from '../src/lib/ttmlParser.ts';
import { cleanTitle, normalizeForMatch, isRelatedMatch, detectRendition } from '../src/services/lyrics.ts';

console.log('--- Testing TTML Parser and AMLL Logic ---');

// 1. parseTtmlTime
assert.strictEqual(parseTtmlTime('27.173'), 27.173);
assert.strictEqual(parseTtmlTime('0:27.173'), 27.173);
assert.strictEqual(parseTtmlTime('3:14.571'), 194.571);
assert.strictEqual(parseTtmlTime('01:02:03.400'), 3723.4);
assert.strictEqual(parseTtmlTime('27s'), 27);
assert.strictEqual(parseTtmlTime('27173ms'), 27.173);
assert.strictEqual(parseTtmlTime(''), 0);
assert.strictEqual(parseTtmlTime(null), 0);
assert.strictEqual(parseTtmlTime(undefined), 0);
console.log('✓ parseTtmlTime tests passed');

// 2. decodeXmlEntities
assert.strictEqual(decodeXmlEntities("I&apos;m"), "I'm");
assert.strictEqual(decodeXmlEntities('&quot;Hello&quot;'), '"Hello"');
assert.strictEqual(decodeXmlEntities('Tom &amp; Jerry'), 'Tom & Jerry');
assert.strictEqual(decodeXmlEntities('&lt;tag&gt;'), '<tag>');
assert.strictEqual(decodeXmlEntities('&#39;test&#39;'), "'test'");
assert.strictEqual(decodeXmlEntities('&#x27;test&#x27;'), "'test'");
console.log('✓ decodeXmlEntities tests passed');

// 3. parseTtml with Word-level Timing
const sampleTtml = `
<tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Word">
  <body dur="3:14.571">
    <div>
      <p begin="27.173" end="28.516">
        <span begin="27.173" end="27.407">I&apos;ve </span>
        <span begin="27.407" end="27.600">been </span>
        <span begin="27.600" end="27.900">tryna </span>
        <span begin="27.900" end="28.516">call</span>
      </p>
      <p begin="29.988" end="34.000">
        <span begin="29.988" end="30.117">I&apos;ve </span>
        <span begin="30.117" end="30.236">been </span>
        <span begin="30.236" end="30.492">on </span>
        <span begin="30.492" end="30.663">my </span>
        <span begin="30.663" end="30.927">own </span>
        <span begin="30.927" end="31.166">for </span>
        <span begin="31.166" end="31.529">long </span>
        <span begin="31.529" end="32.000">enough</span>
      </p>
      <p begin="35.000" end="38.000">(Guitar Solo)</p>
    </div>
  </body>
</tt>
`;

const parsed = parseTtml(sampleTtml);
assert.strictEqual(parsed.duration, 194.571);
assert.strictEqual(parsed.hasWordTiming, true);
assert.strictEqual(parsed.lines.length, 3);

// Line 1 checks
assert.strictEqual(parsed.lines[0].time, 27.173);
assert.strictEqual(parsed.lines[0].text, "I've been tryna call");
assert.strictEqual(parsed.lines[0].words?.length, 4);
assert.strictEqual(parsed.lines[0].words?.[0].word, "I've");
assert.strictEqual(parsed.lines[0].words?.[0].time, 27.173);
assert.strictEqual(parsed.lines[0].words?.[3].word, "call");
assert.strictEqual(parsed.lines[0].words?.[3].time, 27.9);

// Line 2 checks
assert.strictEqual(parsed.lines[1].time, 29.988);
assert.strictEqual(parsed.lines[1].words?.length, 8);

// Line 3 checks (Instrumental)
assert.strictEqual(parsed.lines[2].time, 35.0);
assert.strictEqual(parsed.lines[2].text, "(Guitar Solo)");
assert.strictEqual(parsed.lines[2].isInstrumental, true);
assert.strictEqual(parsed.lines[2].words, undefined);
console.log('✓ parseTtml word timing tests passed');

// 4. parseTtml with Line-level Only Timing
const lineTtml = `
<tt xmlns="http://www.w3.org/ns/ttml">
  <body>
    <div>
      <p begin="10.500" end="15.000">First line of lyrics</p>
      <p begin="16.200" end="20.000">Second line of lyrics</p>
    </div>
  </body>
</tt>
`;
const parsedLine = parseTtml(lineTtml);
assert.strictEqual(parsedLine.hasWordTiming, false);
assert.strictEqual(parsedLine.lines.length, 2);
assert.strictEqual(parsedLine.lines[0].time, 10.5);
assert.strictEqual(parsedLine.lines[0].text, "First line of lyrics");
assert.strictEqual(parsedLine.lines[0].words, undefined);
console.log('✓ parseTtml line-only tests passed');

// 5. Malformed TTML handling
const malformedTtml1 = `<tt><body><div><p begin="invalid">Broken line<span begin="foo">bad</span></p></div></body></tt>`;
const parsedMalformed1 = parseTtml(malformedTtml1);
assert.strictEqual(parsedMalformed1.lines.length, 1);
assert.strictEqual(parsedMalformed1.lines[0].time, 0);

const emptyTtml = parseTtml('');
assert.strictEqual(emptyTtml.lines.length, 0);
assert.strictEqual(emptyTtml.hasWordTiming, false);

const nullTtml = parseTtml(null);
assert.strictEqual(nullTtml.lines.length, 0);
assert.strictEqual(nullTtml.hasWordTiming, false);

const garbageTtml = parseTtml('<<<><<<<not xml>>>');
assert.strictEqual(garbageTtml.lines.length, 0);
assert.strictEqual(garbageTtml.hasWordTiming, false);
console.log('✓ parseTtml malformed & edge case handling passed');

// 6. AMLL Candidate Matching & Rendition Detection
assert.strictEqual(isRelatedMatch('Blinding Lights', 'The Weeknd', 'Blinding Lights', 'The Weeknd'), true);
assert.strictEqual(isRelatedMatch('Shape of You', 'Ed Sheeran', 'Shape of You', 'Ed Sheeran'), true);
assert.strictEqual(isRelatedMatch('Random Song', 'Random Artist', 'Blinding Lights', 'The Weeknd'), false);

const liveRendition = detectRendition('Bohemian Rhapsody - Live Aid 1985');
assert.strictEqual(liveRendition.alternateVersion, true);

const spedUpRendition = detectRendition('Heat Waves (Sped Up)');
assert.strictEqual(spedUpRendition.tempoAltered, true);

const studioTrack = detectRendition('Blinding Lights');
assert.strictEqual(studioTrack.tempoAltered, false);
assert.strictEqual(studioTrack.alternateVersion, false);
console.log('✓ AMLL matching & rendition safety tests passed');

console.log('All TTML & AMLL unit tests passed successfully!');
