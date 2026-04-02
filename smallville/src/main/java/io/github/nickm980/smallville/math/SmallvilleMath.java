package io.github.nickm980.smallville.math;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.robrua.nlp.bert.Bert;

public final class SmallvilleMath {

    private static final Logger LOG = LoggerFactory.getLogger(SmallvilleMath.class);
    private static Bert bert;
    private static boolean bertDisabled = false;

    private SmallvilleMath() {
    }

    private static Bert getBert() {
	if (bertDisabled) {
	    return null;
	}

	if (bert == null) {
	    try {
		bert = Bert.load("com/robrua/nlp/easy-bert/bert-cased-L-12-H-768-A-12");
	    } catch (Throwable t) {
		bertDisabled = true;
		LOG.warn("BERT unavailable. Falling back to lexical similarity: {}", t.toString());
		return null;
	    }
	}
	
	return bert;
    }
    
    public static void loadBert() {
	getBert();
    }
    /**
     * Normalizes a vector to a value between 0 and 1
     * 
     * @param value Any double
     * @return A value between 0 and 1
     */
    public static double normalize(double value, double max, double min) {
	return (value - min) / (max - min);
    }

    /**
     * Calculates the exponential decay
     * 
     * @param original     - the original value
     * @param changeInTime - the change in time
     * @return
     */
    public static double decay(double original, double changeInTime) {
	return original * Math.pow(1 - 0.99, changeInTime);
    }

    /**
     * Calculate the semantical sentence similarity by using bert token embeddings
     * and cosine similarity. If Settings.TOKEN_USAGE are set to high, then open
     * ai's token embedding API will be used for better memory retrieval but higher
     * costs.
     * <p>
     * Might eventually use the openai Embedding API for better results, this method
     * will probably be changed in the future because results aren't that great.
     * 
     * @param a String a to compare
     * @param b String b to compare
     * @return Normalized value between 0 and 1 where 1 is an identical
     */
    public static double calculateSentenceSimilarity(String a, String b) {
	if (a.isEmpty() || b.isEmpty()) {
	    return 0.0;
	}
	float[][] sequenceA = getTextEmbedding(a);
	float[][] sequenceB = getTextEmbedding(b);
	if (sequenceA == null || sequenceB == null || sequenceA.length == 0 || sequenceB.length == 0) {
	    return lexicalSimilarity(a, b);
	}

	float[] embeddingA = getWeightedAverage(sequenceA);
	float[] embeddingB = getWeightedAverage(sequenceB);

	if (embeddingA.length == 0 || embeddingB.length == 0) {
	    return lexicalSimilarity(a, b);
	}

	return cosineSimilarity(embeddingA, embeddingB);
    }

    private static float[] getWeightedAverage(float[][] sequence) {
	if (sequence == null || sequence.length == 0 || sequence[0].length == 0) {
	    return new float[0];
	}

	float[] weights = new float[sequence.length];
	for (int i = 0; i < sequence.length; i++) {
	    weights[i] = 1.0f - ((float) i / sequence.length);
	}
	float[] embedding = new float[sequence[0].length];
	float sum = 0.0f;
	for (int i = 0; i < sequence.length; i++) {
	    for (int j = 0; j < sequence[i].length; j++) {
		embedding[j] += weights[i] * sequence[i][j];
		sum += weights[i];
	    }
	}
	for (int j = 0; j < embedding.length; j++) {
	    embedding[j] /= sum;
	}
	return embedding;
    }

    /**
     * Uses bert to get the token embedding representation. I'm not sure why it
     * returns a float[][] instead of a float[] so this is something that needs to
     * be looked into because it's essential for memory retrieval
     * 
     * @param input
     * @return
     */
    public static float[][] getTextEmbedding(String input) {
	Bert model = getBert();
	if (model == null) {
	    return null;
	}
	try {
	    return model.embedTokens(input);
	} catch (Throwable t) {
	    bertDisabled = true;
	    LOG.warn("BERT embedding failed. Falling back to lexical similarity: {}", t.toString());
	    return null;
	}
    }

    public static double cosineSimilarity(float[] vec1, float[] vec2) {
	if (vec1 == null || vec2 == null || vec1.length == 0 || vec2.length == 0 || vec1.length != vec2.length) {
	    return 0.0;
	}

	double dotProduct = 0.0;
	double norm1 = 0.0;
	double norm2 = 0.0;
	for (int i = 0; i < vec1.length; i++) {
	    dotProduct += vec1[i] * vec2[i];
	    norm1 += vec1[i] * vec1[i];
	    norm2 += vec2[i] * vec2[i];
	}
	double denominator = Math.sqrt(norm1) * Math.sqrt(norm2);
	if (denominator == 0.0) {
	    return 0.0;
	}
	double cosineSimilarity = dotProduct / denominator;
	return normalize(cosineSimilarity, 1, .8);
    }

    private static double lexicalSimilarity(String a, String b) {
	Set<String> tokensA = tokenize(a);
	Set<String> tokensB = tokenize(b);
	if (tokensA.isEmpty() || tokensB.isEmpty()) {
	    return 0.0;
	}

	Set<String> intersection = new HashSet<>(tokensA);
	intersection.retainAll(tokensB);

	Set<String> union = new HashSet<>(tokensA);
	union.addAll(tokensB);

	if (union.isEmpty()) {
	    return 0.0;
	}

	return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenize(String input) {
	Set<String> tokens = new HashSet<>();
	String[] parts = input.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
	for (String part : parts) {
	    if (!part.isBlank()) {
		tokens.add(part);
	    }
	}
	return tokens;
    }
}
