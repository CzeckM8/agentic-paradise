package io.github.nickm980.smallville.memory;

import io.github.nickm980.smallville.math.SmallvilleMath;

public abstract class Memory implements Comparable<Memory> {

    private String description;
    private int weight;

    public Memory(String description) {
	if (description == null) {
	    description = "";
	}
	this.description = description.replace("-", "").trim();
	this.weight = 0;
    }

    /**
     * Calculate the score of a memory based on a query score = a * recency + a *
     * importance + a * relevancy
     * 
     * @param observation
     * @return A value between 0 and 1 where 1 is the strongest score
     */
    public double getScore(String observation) {
	int a1 = 1;
	int a2 = 1;
	int a3 = 1;
	double relevancy = getRelevancy(observation);
	double score = a1 * getRecency() + a2 * getImportance() + a3 * relevancy;

	// Recover from NaN/Inf before applying the keyword boost — if recency produces
	// NaN (e.g. SimulationTime not yet initialised in tests), fall back to relevancy.
	if (!Double.isFinite(score)) {
	    score = Double.isFinite(relevancy) ? relevancy : 0.0;
	}

	// Keyword-match boost: BERT cosine similarity on short phrases is unreliable —
	// exact and substring matches must reliably outrank semantically-nearby but
	// unrelated content. Boosts are additive on top of the base score.
	String query = observation.toLowerCase().trim();
	String desc = description.toLowerCase();
	if (!query.isBlank()) {
	    if (desc.equals(query)) {
		score += 1.0;          // exact match
	    } else if (desc.startsWith(query + " ") || desc.contains(" " + query + " ")
		    || desc.endsWith(" " + query)) {
		score += 0.8;          // whole-word match (query is its own token)
	    } else if (desc.contains(query)) {
		score += 0.6;          // substring match
	    }
	}

	return score;
    }

    /**
     * How recent the memory was. A larger number is more recent
     * 
     * @return double bounded between SIMULATION_START_TIME and SimulationTime.now()
     */
    abstract double getRecency();

    public double getImportance() {
	return weight;
    }

    private double getRelevancy(String observation) {
	return SmallvilleMath.calculateSentenceSimilarity(observation, description);
    }

    public String getDescription() {
	return description;
    }

    public void setDescription(String description) {
	this.description = description;
    }

    public void setImportance(int weight) {
	this.weight = weight;
    }

    @Override
    public int compareTo(Memory o) {
	double score = getScore(description);
	double other = getScore(o.getDescription());
	if (score > other) {
	    return 1;
	}
	if (score < other) {
	    return -1;
	}
	return 0;
    }
}
