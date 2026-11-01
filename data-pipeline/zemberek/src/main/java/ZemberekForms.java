import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.RootLexicon;

/**
 * Reads one Turkish word per line and writes every Zemberek analysis as
 * {@code word \t lemma|pos|morphemes|stem1,stem2,... \t ...}; ingest_zemberek.py picks the links.
 */
public class ZemberekForms {
    public static void main(String[] args) throws Exception {
        TurkishMorphology morphology = TurkishMorphology.createWithDefaults();
        // Spoken spellings (geliyom, yapıcam) only when the standard analysis finds nothing.
        TurkishMorphology informal = TurkishMorphology.builder()
            .setLexicon(RootLexicon.getDefault())
            .useInformalAnalysis()
            .build();
        try (BufferedReader in = Files.newBufferedReader(Paths.get(args[0]), StandardCharsets.UTF_8);
             BufferedWriter out = Files.newBufferedWriter(Paths.get(args[1]), StandardCharsets.UTF_8)) {
            String word;
            int n = 0;
            while ((word = in.readLine()) != null) {
                word = word.trim();
                if (word.isEmpty()) continue;
                WordAnalysis analysis = morphology.analyze(word);
                if (!known(analysis)) analysis = informal.analyze(word);
                StringBuilder line = new StringBuilder(word);
                for (SingleAnalysis a : analysis) {
                    if (a.isUnknown() || a.isRuntime()) continue;
                    List<String> stems = a.getLemmas();
                    line.append('\t').append(a.getDictionaryItem().lemma)
                        .append('|').append(a.getDictionaryItem().primaryPos.shortForm)
                        .append('|').append(a.getMorphemes().size())
                        .append('|').append(String.join(",", stems));
                }
                out.write(line.toString());
                out.newLine();
                if (++n % 100000 == 0) System.err.println(n + " words");
            }
            System.err.println(n + " words done");
        }
    }

    private static boolean known(WordAnalysis analysis) {
        for (SingleAnalysis a : analysis) {
            if (!a.isUnknown() && !a.isRuntime()) return true;
        }
        return false;
    }
}
