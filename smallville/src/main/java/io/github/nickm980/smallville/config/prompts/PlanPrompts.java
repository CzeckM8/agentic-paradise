package io.github.nickm980.smallville.config.prompts;

public class PlanPrompts {

    private String shortTerm;
    private String longTerm;
    private String current;
    private String dailyCommitments;

    public String getShortTerm() {
	return shortTerm;
    }

    public void setShortTerm(String shortTerm) {
	this.shortTerm = shortTerm;
    }

    public String getLongTerm() {
	return longTerm;
    }

    public void setLongTerm(String longTerm) {
	this.longTerm = longTerm;
    }

    public String getCurrent() {
	return current;
    }

    public void setCurrent(String current) {
	this.current = current;
    }

    public String getDailyCommitments() {
	return dailyCommitments;
    }

    public void setDailyCommitments(String dailyCommitments) {
	this.dailyCommitments = dailyCommitments;
    }
}
