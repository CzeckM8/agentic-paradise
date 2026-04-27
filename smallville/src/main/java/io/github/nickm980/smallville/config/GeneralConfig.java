package io.github.nickm980.smallville.config;

public class GeneralConfig {

    private String apiPath;
    private String timeFormat;
    private String fullTimeFormat;
    private String yesterdayFormat;
    private String model;
    private int reflectionCutoff;
    private boolean simulationFile;
    private int maxRetries;

    // ── Agentic tool loop settings ────────────────────────────────────────────
    /** Tick interval in ms for the global simulation clock. Default: 3000 */
    private int simulationTickMs = 3000;
    /** Max tool-call iterations per agent turn. Default: 5 */
    private int agenticMaxIterations = 5;
    /** Wall-clock deadline per turn in ms (0 = no deadline). Default: 8000 */
    private int agenticTurnDeadlineMs = 8000;

    public boolean isSimulationFile() {
	return simulationFile;
    }

    public void setSimulationFile(boolean useSimulationFile) {
	this.simulationFile = useSimulationFile;
    }

    public int getReflectionCutoff() {
	return reflectionCutoff;
    }

    public void setReflectionCutoff(int reflectionCutoff) {
	this.reflectionCutoff = reflectionCutoff;
    }

    public String getModel() {
	return model;
    }

    public void setModel(String model) {
	this.model = model;
    }

    public String getYesterdayFormat() {
	return yesterdayFormat;
    }

    public void setYesterdayFormat(String yesterdayFormat) {
	this.yesterdayFormat = yesterdayFormat;
    }

    public String getFullTimeFormat() {
	return fullTimeFormat;
    }

    public void setFullTimeFormat(String fullTimeFormat) {
	this.fullTimeFormat = fullTimeFormat;
    }

    public String getTimeFormat() {
	return timeFormat;
    }

    public void setTimeFormat(String timeFormat) {
	this.timeFormat = timeFormat;
    }

    public String getApiPath() {
	return apiPath;
    }

    public void setApiPath(String path) {
	this.apiPath = path;
    }

    public int getMaxRetries() {
	return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
	this.maxRetries = maxRetries;
    }

    public int getSimulationTickMs() {
        return simulationTickMs;
    }

    public void setSimulationTickMs(int simulationTickMs) {
        this.simulationTickMs = simulationTickMs;
    }

    public int getAgenticMaxIterations() {
        return agenticMaxIterations;
    }

    public void setAgenticMaxIterations(int agenticMaxIterations) {
        this.agenticMaxIterations = agenticMaxIterations;
    }

    public int getAgenticTurnDeadlineMs() {
        return agenticTurnDeadlineMs;
    }

    public void setAgenticTurnDeadlineMs(int agenticTurnDeadlineMs) {
        this.agenticTurnDeadlineMs = agenticTurnDeadlineMs;
    }

}
