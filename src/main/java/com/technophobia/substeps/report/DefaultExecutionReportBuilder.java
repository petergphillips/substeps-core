/*
 *	Copyright Technophobia Ltd 2012
 *
 *   This file is part of Substeps.
 *
 *    Substeps is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    Substeps is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with Substeps.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.technophobia.substeps.report;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.exception.MethodInvocationException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.joda.time.Duration;
import org.joda.time.format.PeriodFormat;
import org.joda.time.format.PeriodFormatter;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sun.net.www.protocol.file.FileURLConnection;

import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.technophobia.substeps.execution.ExecutionNode;
import com.technophobia.substeps.execution.ExecutionResult;

/**
 * @author ian
 */
public class DefaultExecutionReportBuilder implements ExecutionReportBuilder {

    private final Logger log = LoggerFactory.getLogger(DefaultExecutionReportBuilder.class);

    private final Properties velocityProperties = new Properties();

    public static final String FEATURE_REPORT_FOLDER = "feature_report";
    public static final String JSON_DATA_FILENAME = "report_data.json";
    public static final String JSON_DETAIL_DATA_FILENAME = "detail_data.js";

    private static final String JSON_STATS_DATA_FILENAME = "susbteps-stats.js";

    private static Map<ExecutionResult, String> resultToImageMap = new HashMap<ExecutionResult, String>();

    static {

        resultToImageMap.put(ExecutionResult.PASSED, "imgP");
        resultToImageMap.put(ExecutionResult.NOT_RUN, "imgNR");
        resultToImageMap.put(ExecutionResult.PARSE_FAILURE, "imgPF");
        resultToImageMap.put(ExecutionResult.FAILED, "imgF");

    }

    /**
     * @parameter default-value = ${project.build.directory}
     */
    private File outputDirectory;

    /**
     * @parameter default-value = "Substeps report"
     */
    private String reportTitle;

    public DefaultExecutionReportBuilder() {
        velocityProperties.setProperty("resource.loader", "class");
        velocityProperties.setProperty("class.resource.loader.class",
                "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
    }

    public DefaultExecutionReportBuilder(final File outputDirectory) {
        this();
        this.outputDirectory = outputDirectory;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.technophobia.substeps.report.ExecutionReportBuilder#buildReport(com
     * .technophobia.substeps.report.ReportData, java.io.File)
     */
    public void buildReport(final ReportData data) {

        log.debug("Build report in: " + outputDirectory.getAbsolutePath());

        final File reportDir = new File(outputDirectory + File.separator + FEATURE_REPORT_FOLDER);

        try {

            log.debug("trying to create: " + reportDir.getAbsolutePath());

            if (reportDir.exists()) {
                FileUtils.deleteDirectory(reportDir);
            }

            Assert.assertTrue("failed to create directory: " + reportDir, reportDir.mkdir());

            copyStaticResources(reportDir);

            buildMainReport(data, reportDir);

            buildTreeJSON(data, reportDir);

            buildDetailJSON(data, reportDir);

            buildStatsJSON(data, reportDir);

        } catch (final IOException ex) {
            log.error("IOException: ", ex);
        } catch (final URISyntaxException ex) {
            log.error("URISyntaxException: ", ex);
        }
    }

    /**
     * @param data
     * @param reportDir
     */
    private void buildStatsJSON(final ReportData data, final File reportDir) throws IOException {

        final File jsonFile = new File(reportDir, JSON_STATS_DATA_FILENAME);

        final ExecutionStats stats = new ExecutionStats();
        stats.buildStats(data);

        final BufferedWriter writer = Files.newWriter(jsonFile, Charset.defaultCharset());
        try {
            buildStatsJSON(stats, writer);
        } finally {
            writer.close();
        }

    }

    /**
     * @param stats
     * @param writer
     */
    private void buildStatsJSON(final ExecutionStats stats, final BufferedWriter writer) throws IOException {

        writer.append("var featureStatsData = [");
        boolean first = true;

        for (final TestCounterSet stat : stats.getSortedList()) {

            if (!first) {
                writer.append(",\n");
            }
            writer.append("[\"").append(stat.getTag()).append("\",");
            writer.append("\"").append(Integer.toString(stat.getFeatureStats().getCount())).append("\",");
            writer.append("\"").append(Integer.toString(stat.getFeatureStats().getRun())).append("\",");
            writer.append("\"").append(Integer.toString(stat.getFeatureStats().getPassed())).append("\",");
            writer.append("\"").append(Integer.toString(stat.getFeatureStats().getFailed())).append("\",");
            writer.append("\"").append(Double.toString(stat.getFeatureStats().getSuccessPc())).append("\"]");

            first = false;
        }

        writer.append("];\n");

        writer.append("var scenarioStatsData = [");
        first = true;

        for (final TestCounterSet stat : stats.getSortedList()) {

            if (!first) {
                writer.append(",\n");
            }
            writer.append("[\"").append(stat.getTag()).append("\",");
            writer.append("\"").append(Integer.toString(stat.getScenarioStats().getCount())).append("\",");
            writer.append("\"").append(Integer.toString(stat.getScenarioStats().getRun())).append("\",");
            writer.append("\"").append(Integer.toString(stat.getScenarioStats().getPassed())).append("\",");
            writer.append("\"").append(Integer.toString(stat.getScenarioStats().getFailed())).append("\",");
            writer.append("\"").append(Double.toString(stat.getScenarioStats().getSuccessPc())).append("\"")
                    .append("]");

            first = false;
        }

        writer.append("];\n");

    }

    /**
     * @param data
     * @param reportDir
     * @throws IOException
     */
    private void buildDetailJSON(final ReportData data, final File reportDir) throws IOException {
        final File jsonFile = new File(reportDir, JSON_DETAIL_DATA_FILENAME);

        final BufferedWriter writer = Files.newWriter(jsonFile, Charset.defaultCharset());
        try {
            buildDetailJSON(data, writer);
        } finally {
            writer.close();
        }

    }

    private void buildTreeJSON(final ReportData reportData, final File reportDir) throws IOException {
        log.debug("Building tree json file.");

        final File jsonFile = new File(reportDir, JSON_DATA_FILENAME);

        final Writer writer = new BufferedWriter(new FileWriter(jsonFile));

        final List<ExecutionNode> nodeList = reportData.getRootNodes();

        boolean rootNodeInError = false;

        try {
            if (!nodeList.isEmpty()) {

                for (final ExecutionNode rootNode : nodeList) {

                    rootNodeInError = rootNode.hasError();
                    if (rootNodeInError) {
                        break;
                    }
                }

                writer.append("var treeData =  { \"data\" : { \"title\" : \"Substeps tests\", \"attr\" : { \"id\" : \"0\" }, ");

                if (rootNodeInError) {

                    writer.append("\"icon\" : imgF, \"state\" : \"open\"}, \"children\" : [");

                } else {
                    writer.append("\"icon\" : imgP}, \"children\" : [");
                }

                boolean first = true;
                for (final ExecutionNode rootNode : nodeList) {

                    if (!first) {
                        writer.append(",\n");
                    }

                    buildNodeJSON(rootNode, writer);
                    first = false;
                }

                writer.append("]};\n");

            }

        } finally {
            writer.close();
        }

    }

    /**
     * @param reportData
     * @param writer
     * @throws IOException
     */
    private void buildDetailJSON(final ReportData reportData, final Writer writer) throws IOException {

        writer.append("var detail = new Array();");

        for (final ExecutionNode node : reportData.getRootNodes()) {
            buildDetailJSON(node, writer);
        }

    }

    private String replaceNewLines(final String s) {

        if (s != null && s.contains("\n")) {

            return s.replaceAll("\n", "<br/>");
        } else {
            return s;
        }
    }

    /**
     * @param node
     * @param writer
     */
    private void buildDetailJSON(final ExecutionNode node, final Writer writer) throws IOException {

        List<JsonObject> allNodesAsJson = createDetailJsonObjects(node);

        for (JsonObject nodeAsJson : allNodesAsJson) {
            writer.append("\ndetail[" + nodeAsJson.get("id") + "]=" + nodeAsJson.toString() + ";");
        }

    }

    private List<JsonObject> createDetailJsonObjects(ExecutionNode node) {

        List<JsonObject> nodeAndChildren = Lists.newArrayList();

        JsonObject thisNode = new JsonObject();
        nodeAndChildren.add(thisNode);

        thisNode.addProperty("nodetype", node.getType());
        thisNode.addProperty("filename", node.getFilename());
        thisNode.addProperty("result", node.getResult().getResult().toString());
        thisNode.addProperty("id", node.getId());
        thisNode.addProperty("emessage", getExceptionMessage(node));
        thisNode.addProperty("stacktrace", getStackTrace(node));

        thisNode.addProperty("runningDurationMillis", node.getResult().getRunningDuration());
        thisNode.addProperty("runningDurationString", convert(node.getResult().getRunningDuration()));

        String methodInfo = createMethodInfo(node);

        thisNode.addProperty("method", methodInfo);

        String description = node.getDescription() == null ? null : node.getDescription().trim();
        String descriptionEscaped = replaceNewLines(StringEscapeUtils.escapeHtml4(description));

        thisNode.addProperty("description", descriptionEscaped);

        JsonArray children = new JsonArray();
        if (node.hasChildren()) {
            addDetailsForChildren(node, children);
        }
        thisNode.add("children", children);

        if (node.hasChildren()) {

            for (final ExecutionNode childNode : node.getChildren()) {

                nodeAndChildren.addAll(createDetailJsonObjects(childNode));
            }
        }

        return nodeAndChildren;
    }

    private String convert(Long runningDurationMillis) {

        return runningDurationMillis == null ? "No duration recorded" : convert(runningDurationMillis.longValue());
    }

    private String convert(long runningDurationMillis) {
        Duration duration = new Duration(runningDurationMillis);
        PeriodFormatter formatter = PeriodFormat.getDefault();
        return formatter.print(duration.toPeriod());
    }

    private void addDetailsForChildren(ExecutionNode node, JsonArray children) {
        for (ExecutionNode childNode : node.getChildren()) {
            JsonObject childObject = new JsonObject();
            childObject.addProperty("result", childNode.getResult().getResult().toString());
            childObject.addProperty("description", StringEscapeUtils.escapeHtml4(childNode.getDescription()));
            children.add(childObject);
        }
    }

    private String createMethodInfo(ExecutionNode node) {

        final StringBuilder methodInfoBuffer = new StringBuilder();
        node.appendMethodInfo(methodInfoBuffer);

        String methodInfo = methodInfoBuffer.toString();
        if (methodInfo.contains("\"")) {
            methodInfo = methodInfo.replace("\"", "\\\"");
        }

        return replaceNewLines(methodInfo);
    }

    private String getExceptionMessage(ExecutionNode node) {
        String exceptionMessage = "";

        if (node.getResult().getThrown() != null) {

            final String exceptionMsg = StringEscapeUtils.escapeHtml4(node.getResult().getThrown().getMessage());

            exceptionMessage = replaceNewLines(exceptionMsg);

        }

        return exceptionMessage;
    }

    private String getStackTrace(ExecutionNode node) {
        String stackTrace = "";

        if (node.getResult().getThrown() != null) {

            final StackTraceElement[] stackTraceElements = node.getResult().getThrown().getStackTrace();

            final StringBuilder buf = new StringBuilder();

            for (final StackTraceElement e : stackTraceElements) {

                buf.append(StringEscapeUtils.escapeHtml4(e.toString().trim())).append("<br/>");
            }
            stackTrace = buf.toString();
        }

        return stackTrace;
    }

    private void buildNodeJSON(final ExecutionNode node, final Writer writer) throws IOException {

        writer.append("{ ");

        /***** Data object *****/
        writer.append("\"data\" : { ");

        writer.append("\"title\" : \"");
        writer.append(getDescriptionForNode(node));
        writer.append("\"");

        writer.append(", \"attr\" : { \"id\" : \"");
        writer.append(Long.toString(node.getId()));
        writer.append("\" }");

        writer.append(", \"icon\" : ");
        writer.append(getNodeImage(node));

        writer.append("}");
        /***** END: Data object *****/

        if (node.hasChildren()) {
            if (node.hasError()) {
                writer.append(", \"state\" : \"open\"");
            }
            writer.append(", \"children\" : [");
            boolean first = true;
            for (final ExecutionNode child : node.getChildren()) {
                if (!first) {
                    writer.append(", ");
                }
                buildNodeJSON(child, writer);
                first = false;
            }
            writer.append("]");
        }

        writer.append("}");

    }

    /**
     * @param reportDir
     * @throws IOException
     */
    private void copyStaticResources(final File reportDir) throws URISyntaxException, IOException {

        log.debug("Copying static resources to: " + reportDir.getAbsolutePath());

        final URL staticURL = getClass().getResource("/static");
        if (staticURL == null) {
            throw new IllegalStateException("Failed to copy static resources for report.  URL for resources is null.");
        }

        copyResourcesRecursively(staticURL, reportDir);
    }

    private String getNodeImage(final ExecutionNode node) {
        return resultToImageMap.get(node.getResult().getResult());
    }

    private String getDescriptionForNode(final ExecutionNode node) {
        final StringBuilder buf = new StringBuilder();

        if (node.getParent() == null) {
            if (node.getLine() != null) {
                buf.append(node.getLine());
            } else {
                buf.append("executionNodeRoot");
            }
        } else {

            buildDescriptionString(null, node, buf);

        }
        // return StringEscapeUtils.escapeHtml4(buf.toString());
        // no need to escape this

        // need to replace "
        String msg = buf.toString();
        if (msg.contains("\"")) {
            msg = msg.replace("\"", "\\\"");
        }

        return msg;
    }

    public static void buildDescriptionString(final String prefix, final ExecutionNode node, final StringBuilder buf) {
        if (prefix != null) {
            buf.append(prefix);
        }

        if (node.getFeature() != null) {

            buf.append(node.getFeature().getName());

        } else if (node.getScenarioName() != null) {

            if (node.isOutlineScenario()) {
                buf.append("Scenario #: ");
            } else {
                buf.append("Scenario: ");
            }
            buf.append(node.getScenarioName());
        }

        if (node.getParent() != null && node.getParent().isOutlineScenario()) {

            buf.append(node.getRowNumber()).append(" ").append(node.getParent().getScenarioName()).append(":");
        }

        if (node.getLine() != null) {
            buf.append(node.getLine());
        }
    }

    private void buildMainReport(final ReportData data, final File reportDir) throws IOException {

        log.debug("Building main report file.");

        final VelocityContext vCtx = new VelocityContext();

        final String vml = "report_frame.vm";

        final ExecutionStats stats = new ExecutionStats();
        stats.buildStats(data);

        final SimpleDateFormat sdf = new SimpleDateFormat("EEE dd MMM yyyy HH:mm");
        final String dateTimeStr = sdf.format(new Date());

        vCtx.put("stats", stats);
        vCtx.put("dateTimeStr", dateTimeStr);
        vCtx.put("reportTitle", reportTitle);

        renderAndWriteToFile(reportDir, vCtx, vml, "report_frame.html");

    }

    /**
     * @param reportDir
     * @param vCtx
     * @param vm
     * @param targetFilename
     * @throws IOException
     */
    private void renderAndWriteToFile(final File reportDir, final VelocityContext vCtx, final String vm,
            final String targetFilename) throws IOException {

        final Writer writer = new BufferedWriter(new FileWriter(new File(reportDir, targetFilename)));

        final VelocityEngine velocityEngine = new VelocityEngine();

        try {

            velocityEngine.init(velocityProperties);
            velocityEngine.getTemplate("templates/" + vm).merge(vCtx, writer);

        } catch (final ResourceNotFoundException e) {
            throw new RuntimeException(e);
        } catch (final ParseErrorException e) {
            throw new RuntimeException(e);
        } catch (final MethodInvocationException e) {
            throw new RuntimeException(e);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (final IOException e) {

                log.error("IOException: ", e);
            }
        }
    }

    public void copyResourcesRecursively(final URL originUrl, final File destination) throws IOException {
        final URLConnection urlConnection = originUrl.openConnection();
        if (urlConnection instanceof JarURLConnection) {
            copyJarResourcesRecursively(destination, (JarURLConnection) urlConnection);
        } else if (urlConnection instanceof FileURLConnection) {
            FileUtils.copyDirectory(new File(originUrl.getPath()), destination);
        } else {
            throw new RuntimeException("URLConnection[" + urlConnection.getClass().getSimpleName()
                    + "] is not a recognized/implemented connection type.");
        }
    }

    public void copyJarResourcesRecursively(final File destination, final JarURLConnection jarConnection)
            throws IOException {
        final JarFile jarFile = jarConnection.getJarFile();
        for (final JarEntry entry : Collections.list(jarFile.entries())) {
            if (entry.getName().startsWith(jarConnection.getEntryName())) {
                final String fileName = StringUtils.removeStart(entry.getName(), jarConnection.getEntryName());
                if (!entry.isDirectory()) {
                    InputStream entryInputStream = null;
                    try {
                        entryInputStream = jarFile.getInputStream(entry);
                        FileUtils.copyInputStreamToFile(entryInputStream, new File(destination, fileName));
                    } finally {
                        IOUtils.closeQuietly(entryInputStream);
                    }
                } else {
                    new File(destination, fileName).mkdirs();
                }
            }
        }
    }

}
