package io.github.nickm980.smallville.entities;

public class ActionHistory {

    private String activity;
    private String lastActivity;
    private String emoji;
    private double stressLevel = 0.0;  // 0.0 (calm) to 1.0 (panic)
    private String mentalState = "calm";  // calm, anxious, panicked, aggressive
    
    public ActionHistory(String action) {
	this.activity = action;
 	this.lastActivity = action;
    }

    public String getActivity() {
	return activity;
    }

    public String getLastActivity() {
	return lastActivity;
    }

    public void setActivity(String activity) {
	this.lastActivity = this.activity;
	this.activity = activity;
    }

    public void setEmoji(String emoji) {
	this.emoji = emoji;
    }

    public String getEmoji() {
	return emoji;
    }

    public double getStressLevel() {
	return stressLevel;
    }

    public void setStressLevel(double stressLevel) {
	this.stressLevel = Math.max(0.0, Math.min(1.0, stressLevel));
	updateMentalState();
    }

    public String getMentalState() {
	return mentalState;
    }

    public void setMentalState(String mentalState) {
	this.mentalState = mentalState;
    }

    public void restoreState(String activity, String lastActivity, String emoji,
	    double stressLevel, String mentalState) {
	this.activity = activity;
	this.lastActivity = lastActivity == null ? activity : lastActivity;
	this.emoji = emoji;
	this.stressLevel = Math.max(0.0, Math.min(1.0, stressLevel));
	this.mentalState = mentalState == null || mentalState.isBlank() ? this.mentalState : mentalState;
    }

    /**
     * Updates mental state based on current stress level
     */
    private void updateMentalState() {
	if (stressLevel < 0.2) {
	    this.mentalState = "calm";
	} else if (stressLevel < 0.5) {
	    this.mentalState = "anxious";
	} else if (stressLevel < 0.8) {
	    this.mentalState = "panicked";
	} else {
	    this.mentalState = "aggressive";
	}
    }

    /**
     * Apply stress change to current stress level
     * @param delta Change in stress (-1.0 to +1.0)
     */
    public void applyStressChange(double delta) {
	this.setStressLevel(stressLevel + delta);
    }
}
