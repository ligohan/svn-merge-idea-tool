package com.svnmerge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * SVN 命令封装，负责执行 svn log 和 svn merge 命令
 */
public class SvnCommandExecutor {

    private static final int TIMEOUT_SECONDS = 30;

    /**
     * 常见的 svn 安装路径，IDEA 子进程的 PATH 可能不完整
     */
    private static final String[] SVN_SEARCH_PATHS = {
            "/usr/local/bin/svn",
            "/opt/homebrew/bin/svn",
            "/usr/bin/svn",
    };

    private volatile String svnPath;

    /**
     * 查找可用的 svn 可执行文件路径
     */
    private String findSvn() {
        if (svnPath != null) return svnPath;
        for (String path : SVN_SEARCH_PATHS) {
            if (new java.io.File(path).canExecute()) {
                svnPath = path;
                return path;
            }
        }
        // 兜底，依赖 PATH
        svnPath = "svn";
        return svnPath;
    }

    /**
     * 命令执行结果
     */
    public static class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /**
     * 获取当前工作副本的 SVN URL
     *
     * @param workingDir 工作目录
     * @return SVN URL，失败返回 null
     */
    public String queryWorkingCopyUrl(String workingDir) {
        Result result = execute(workingDir, findSvn(), "info", "--xml", ".");
        if (!result.isSuccess()) return null;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(result.stdout)));
            NodeList urlNodes = doc.getElementsByTagName("url");
            if (urlNodes.getLength() > 0) {
                return urlNodes.item(0).getTextContent().trim();
            }
        } catch (Exception e) {
            // 忽略解析异常
        }
        return null;
    }

    /**
     * 从 IDEA 项目配置文件 .idea/misc.xml 中读取 SvnBranchConfigurationManager 的 trunkUrl。
     *
     * @param workingDir 项目根目录
     * @return trunkUrl 值，找不到则返回 null
     */
    public String queryTrunkUrl(String workingDir) {
        if (workingDir == null) return null;
        java.io.File miscFile = new java.io.File(workingDir, ".idea/misc.xml");
        if (!miscFile.exists()) return null;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(miscFile);
            NodeList components = doc.getElementsByTagName("component");
            for (int i = 0; i < components.getLength(); i++) {
                Element comp = (Element) components.item(i);
                if ("SvnBranchConfigurationManager".equals(comp.getAttribute("name"))) {
                    NodeList options = comp.getElementsByTagName("option");
                    for (int j = 0; j < options.getLength(); j++) {
                        Element opt = (Element) options.item(j);
                        if ("trunkUrl".equals(opt.getAttribute("name"))) {
                            String value = opt.getAttribute("value");
                            if (value != null && !value.isEmpty()) {
                                return value;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略解析异常
        }
        return null;
    }

    /**
     * 查询指定版本号的 svn log
     *
     * @param branchUrl 源分支 URL
     * @param revision  版本号
     * @return 命令执行结果
     */
    public Result queryLog(String branchUrl, String revision) {
        return execute(null, findSvn(), "log", "-r", revision, branchUrl);
    }

    /**
     * 执行 svn merge
     *
     * @param workingDir 工作目录
     * @param branchUrl  源分支 URL
     * @param revision   版本号
     * @return 命令执行结果
     */
    public Result merge(String workingDir, String branchUrl, String revision) {
        return execute(workingDir, findSvn(), "merge", "-c", revision, branchUrl);
    }

    /**
     * 执行 svn update
     *
     * @param workingDir 工作目录
     * @return 命令执行结果
     */
    public Result update(String workingDir) {
        return execute(workingDir, findSvn(), "update");
    }

    /**
     * 执行 svn update，并实时回调输出行
     *
     * @param workingDir   工作目录
     * @param outputLineCb 输出行回调（stdout/stderr）
     * @return 命令执行结果
     */
    public Result update(String workingDir, Consumer<String> outputLineCb) {
        return executeRealtime(workingDir, outputLineCb, findSvn(), "update");
    }

    /**
     * svn log 条目数据
     */
    public static class LogEntry {
        public final String revision;
        public final String author;
        public final String message;
        public final String commitTime;
        public final List<String> changedFiles;
        public boolean merged;

        public LogEntry(String revision, String author, String message, String commitTime, List<String> changedFiles) {
            this.revision = revision;
            this.author = author;
            this.message = message;
            this.commitTime = commitTime;
            this.changedFiles = changedFiles;
        }
    }

    /**
     * 查询指定版本号的详细 svn log（含变动文件列表）
     *
     * @param branchUrl 源分支 URL
     * @param revision  版本号
     * @return LogEntry，查询失败时 author 为空
     */
    public LogEntry queryLogVerbose(String branchUrl, String revision) {
        Result result = execute(null, findSvn(), "log", "--xml", "-v", "-r", revision, branchUrl);
        if (!result.isSuccess()) {
            return new LogEntry(revision, "", "查询失败：" + result.stderr, "", new ArrayList<>());
        }
        return parseLogXml(result.stdout, revision);
    }

    /**
     * 解析 svn log --xml -v 的 XML 输出
     */
    private LogEntry parseLogXml(String xmlOutput, String revision) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlOutput)));
            NodeList entries = doc.getElementsByTagName("logentry");
            if (entries.getLength() == 0) {
                return new LogEntry(revision, "", "未找到日志", "", new ArrayList<>());
            }
            Element entry = (Element) entries.item(0);
            String author = getTagText(entry, "author");
            String message = getTagText(entry, "msg");
            String commitTime = getTagText(entry, "date");

            List<String> changedFiles = new ArrayList<>();
            NodeList paths = entry.getElementsByTagName("path");
            for (int i = 0; i < paths.getLength(); i++) {
                Element pathEl = (Element) paths.item(i);
                String action = pathEl.getAttribute("action");
                String path = pathEl.getTextContent();
                changedFiles.add(action + " " + path);
            }
            return new LogEntry(revision, author, message, commitTime, changedFiles);
        } catch (Exception e) {
            return new LogEntry(revision, "", "XML 解析失败：" + e.getMessage(), "", new ArrayList<>());
        }
    }

    private static String getTagText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return "";
    }

    private static final int SEARCH_RESULT_MAX = 30;

    /**
     * 搜索结果，包含条目列表或错误信息
     */
    public static class SearchResult {
        public final List<LogEntry> entries;
        public final String error;

        private SearchResult(List<LogEntry> entries, String error) {
            this.entries = entries;
            this.error = error;
        }

        public boolean isSuccess() {
            return error == null;
        }

        public static SearchResult success(List<LogEntry> entries) {
            return new SearchResult(entries, null);
        }

        public static SearchResult failure(String error) {
            return new SearchResult(new ArrayList<>(), error);
        }
    }

    /**
     * 加载源分支最新的 N 条提交记录。
     *
     * @param branchUrl 源分支 URL
     * @param limit     最多加载的条数
     * @return SearchResult，成功时包含条目列表，失败时包含错误信息
     */
    public SearchResult queryLatestLog(String branchUrl, int limit) {
        Result result = execute(null, findSvn(), "log", "--xml", "-v",
                "--limit", String.valueOf(limit), branchUrl);
        if (!result.isSuccess()) {
            return SearchResult.failure("加载失败：" + decodeUnicodeEscapes(result.stderr));
        }
        List<LogEntry> entries = parseLogXmlMultiple(result.stdout);
        return SearchResult.success(entries);
    }

    /**
     * 按关键字搜索指定版本范围内的提交记录。
     * 如果版本范围包含算术表达式（如 HEAD-1000），会先解析 HEAD 版本号再计算。
     *
     * @param branchUrl     源分支 URL
     * @param keyword       搜索关键字
     * @param author        作者（可选）
     * @param revisionRange 版本范围，如 "HEAD:HEAD-1000" 或 "1000:2000"
     * @return SearchResult，成功时包含条目列表（最多 30 条），失败时包含错误信息
     */
    public SearchResult queryLogBySearch(String branchUrl, String keyword, String author, String revisionRange) {
        // 解析版本范围中的 HEAD 算术表达式
        String resolvedRange = resolveRevisionRange(branchUrl, revisionRange);
        if (resolvedRange == null) {
            return SearchResult.failure("无法解析版本范围，请检查格式是否正确");
        }

        List<String> args = new ArrayList<>();
        args.add(findSvn());
        args.add("log");
        args.add("--xml");
        args.add("-v");
        args.add("-r");
        args.add(resolvedRange);
        if (keyword != null && !keyword.trim().isEmpty()) {
            args.add("--search");
            args.add(keyword);
        }
        if (author != null && !author.trim().isEmpty()) {
            if (keyword != null && !keyword.trim().isEmpty()) {
                args.add("--search-and");
            } else {
                args.add("--search");
            }
            args.add(author);
        }
        args.add(branchUrl);

        Result result = execute(null, args.toArray(new String[0]));
        if (!result.isSuccess()) {
            return SearchResult.failure("搜索失败：" + decodeUnicodeEscapes(result.stderr));
        }
        List<LogEntry> entries = parseLogXmlMultiple(result.stdout);
        if (entries.size() > SEARCH_RESULT_MAX) {
            entries = new ArrayList<>(entries.subList(0, SEARCH_RESULT_MAX));
        }
        return SearchResult.success(entries);
    }

    /**
     * 解析版本范围中的 HEAD 算术表达式。
     * 支持格式：HEAD:HEAD-1000、HEAD:100、1000:2000、HEAD 等。
     */
    private String resolveRevisionRange(String branchUrl, String range) {
        if (range == null || range.trim().isEmpty()) return null;
        // 如果不包含 HEAD 算术表达式，直接返回
        if (!range.matches(".*HEAD\\s*[+-]\\s*\\d+.*")) {
            return range;
        }
        // 需要获取 HEAD 版本号
        Result infoResult = execute(null, findSvn(), "info", "--xml", branchUrl);
        if (!infoResult.isSuccess()) return null;
        long headRev;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(infoResult.stdout)));
            // <entry revision="12345">
            String revStr = doc.getDocumentElement()
                    .getElementsByTagName("entry").item(0)
                    .getAttributes().getNamedItem("revision").getNodeValue();
            headRev = Long.parseLong(revStr);
        } catch (Exception e) {
            return null;
        }
        // 替换 HEAD+N / HEAD-N
        String resolved = range;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("HEAD\\s*([+-])\\s*(\\d+)").matcher(resolved);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String op = m.group(1);
            long offset = Long.parseLong(m.group(2));
            long val = "+".equals(op) ? headRev + offset : headRev - offset;
            if (val < 1) val = 1;
            m.appendReplacement(sb, String.valueOf(val));
        }
        m.appendTail(sb);
        // 替换剩余的纯 HEAD
        return sb.toString().replace("HEAD", String.valueOf(headRev));
    }

    /**
     * 解析包含多条记录的 svn log --xml -v 输出
     */
    private List<LogEntry> parseLogXmlMultiple(String xmlOutput) {
        List<LogEntry> entries = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlOutput)));
            NodeList logEntries = doc.getElementsByTagName("logentry");
            for (int i = 0; i < logEntries.getLength(); i++) {
                Element entry = (Element) logEntries.item(i);
                String revision = entry.getAttribute("revision");
                String author = getTagText(entry, "author");
                String message = getTagText(entry, "msg");
                String commitTime = getTagText(entry, "date");

                List<String> changedFiles = new ArrayList<>();
                NodeList paths = entry.getElementsByTagName("path");
                for (int j = 0; j < paths.getLength(); j++) {
                    Element pathEl = (Element) paths.item(j);
                    String action = pathEl.getAttribute("action");
                    String path = pathEl.getTextContent();
                    changedFiles.add(action + " " + path);
                }
                entries.add(new LogEntry(revision, author, message, commitTime, changedFiles));
            }
        } catch (Exception e) {
            entries.add(new LogEntry("", "", "XML 解析失败：" + e.getMessage(), "", new ArrayList<>()));
        }
        return entries;
    }

    /**
     * 解析 svn log -v 输出
     * <pre>
     * ------------------------------------------------------------------------
     * r12345 | author | 2024-01-01 12:00:00 +0800 | 1 line
     * Changed paths:
     *    M /branches/xxx/src/Main.java
     *    A /branches/xxx/src/New.java
     *
     * 提交信息
     * ------------------------------------------------------------------------
     * </pre>
     */
    private LogEntry parseLogVerbose(String logOutput, String revision) {
        String[] lines = logOutput.split("\n");
        String author = "";
        String commitTime = "";
        List<String> messageLines = new ArrayList<>();
        List<String> changedFiles = new ArrayList<>();
        boolean inChangedPaths = false;
        boolean headerParsed = false;
        boolean changedPathsDone = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.matches("^-{4,}$")) continue;
            // 解析 header 行
            if (!headerParsed && line.startsWith("r") && line.contains("|")) {
                String[] parts = line.split("\\|");
                author = parts.length > 1 ? parts[1].trim() : "unknown";
                commitTime = parts.length > 2 ? parts[2].trim() : "";
                headerParsed = true;
                continue;
            }
            if (line.startsWith("Changed paths") || line.startsWith("改变的路径")) {
                inChangedPaths = true;
                continue;
            }
            if (inChangedPaths) {
                if (line.isEmpty()) {
                    inChangedPaths = false;
                    changedPathsDone = true;
                } else {
                    changedFiles.add(decodeUnicodeEscapes(line));
                }
                continue;
            }
            // header 解析后、Changed paths 之前的空行跳过
            if (headerParsed && !changedPathsDone && line.isEmpty()) {
                continue;
            }
            // 收集提交信息（Changed paths 结束后，或者没有 Changed paths 时 header 后的内容）
            if (headerParsed && !line.isEmpty()) {
                messageLines.add(decodeUnicodeEscapes(line));
            }
        }
        String message = String.join("\n", messageLines);

        if (author.isEmpty()) {
            return new LogEntry(revision, "", "查询失败", commitTime, changedFiles);
        }
        return new LogEntry(revision, author, message, commitTime, changedFiles);
    }

    /**
     * 获取指定版本的文件内容
     *
     * @param branchUrl 源分支 URL
     * @param revision  版本号
     * @param filePath  相对文件路径
     * @return 文件内容，失败时返回 null
     */
    public String queryFileContent(String branchUrl, String revision, String filePath) {
        String fileUrl = branchUrl.endsWith("/")
                ? branchUrl + filePath
                : branchUrl + "/" + filePath;
        Result result = execute(null, findSvn(), "cat", "-r", revision, fileUrl);
        return result.isSuccess() ? result.stdout : null;
    }

    /**
     * 查询指定版本号某个文件的 diff
     *
     * @param branchUrl 源分支 URL
     * @param revision  版本号
     * @return diff 文本内容
     */
    public String queryDiff(String branchUrl, String revision) {
        Result result = execute(null, findSvn(), "diff", "-c", revision, branchUrl);
        if (result.isSuccess()) {
            return decodeUnicodeEscapes(result.stdout);
        }
        return "获取 diff 失败：" + result.stderr;
    }

    /**
     * 查询指定版本号某个文件的 diff
     *
     * @param branchUrl 源分支 URL
     * @param revision  版本号
     * @param filePath  相对文件路径
     * @return diff 文本内容
     */
    public String queryFileDiff(String branchUrl, String revision, String filePath) {
        String fileUrl = branchUrl.endsWith("/")
                ? branchUrl + filePath
                : branchUrl + "/" + filePath;
        Result result = execute(null, findSvn(), "diff", "-c", revision, fileUrl);
        if (result.isSuccess()) {
            return decodeUnicodeEscapes(result.stdout);
        }
        return "获取 diff 失败：" + result.stderr;
    }

    /**
     * 解析 svn log 输出，提取单行摘要
     * 格式：r版本号 | 作者 | 提交信息
     */
    public static String parseLogSummary(String logOutput, String revision) {
        // svn log 输出格式：
        // ------------------------------------------------------------------------
        // r12345 | author | 2024-01-01 12:00:00 +0800 (Mon, 01 Jan 2024) | 1 line
        //
        // 提交信息
        // ------------------------------------------------------------------------
        String[] lines = logOutput.split("\n");
        String header = "";
        String message = "";

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("r") && line.contains("|")) {
                header = line;
            } else if (!line.isEmpty() && !line.startsWith("---")) {
                if (header.isEmpty()) continue;
                // 第一行非空非分隔线的内容就是提交信息
                message = line;
                break;
            }
        }

        if (header.isEmpty()) {
            return "r" + revision + " | 查询失败";
        }

        // 从 header 提取作者
        String[] parts = header.split("\\|");
        // 去掉版本号前面的 r
        String rev = parts[0].trim();
        if (rev.startsWith("r")) rev = rev.substring(1);
        String author = parts.length > 1 ? parts[1].trim() : "unknown";

        return rev + " | " + author + " | " + decodeUnicodeEscapes(message);
    }

    /**
     * 将 {U+XXXX} 格式的 Unicode 转义还原为中文字符
     */
    public static String decodeUnicodeEscapes(String input) {
        if (input == null || !input.contains("{U+")) return input;
        Matcher matcher = Pattern.compile("\\{U\\+([0-9A-Fa-f]{4,5})\\}").matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            int codePoint = Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(sb, new String(Character.toChars(codePoint)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 通过 svn:mergeinfo 属性判断指定版本号是否已合并
     * <p>
     * 读取 svn:mergeinfo 属性（一个紧凑的范围字符串），在本地解析判断，
     * 比 svn mergeinfo --show-revs 快得多，不受历史版本数量影响。
     *
     * @param workingDir 工作目录（目标分支的工作副本）
     * @param branchUrl  源分支 URL
     * @param revisions  需要检查的版本号列表
     * @return 已合并的版本号集合（是 revisions 的子集）
     */
    public Set<String> queryMergedRevisions(String workingDir, String branchUrl, List<String> revisions) {
        Set<String> merged = new HashSet<>();
        // 读取 svn:mergeinfo 属性
        Result result = execute(workingDir, findSvn(), "propget", "svn:mergeinfo", ".");
        if (!result.isSuccess() || result.stdout.trim().isEmpty()) {
            return merged;
        }

        // 从 branchUrl 提取仓库内路径，用于匹配 mergeinfo 中的条目
        // mergeinfo 格式：/branches/xxx:100-200,300,400-500
        String branchPath = extractRepoPath(branchUrl);

        // 解析 mergeinfo，找到匹配源分支的行
        Set<Long> mergedRevNums = new HashSet<>();
        for (String line : result.stdout.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int colonIdx = trimmed.lastIndexOf(':');
            if (colonIdx < 0) continue;
            String path = trimmed.substring(0, colonIdx).trim();
            String rangeStr = trimmed.substring(colonIdx + 1).trim();
            // 路径匹配（mergeinfo 中的路径是仓库内相对路径）
            if (branchPath != null && path.equals(branchPath)) {
                parseMergeRanges(rangeStr, mergedRevNums);
            }
        }

        // 如果没有通过路径匹配到，尝试用分支名模糊匹配
        if (mergedRevNums.isEmpty() && branchPath != null) {
            String branchName = branchPath.substring(branchPath.lastIndexOf('/') + 1);
            for (String line : result.stdout.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                int colonIdx = trimmed.lastIndexOf(':');
                if (colonIdx < 0) continue;
                String path = trimmed.substring(0, colonIdx).trim();
                if (path.endsWith("/" + branchName)) {
                    parseMergeRanges(trimmed.substring(colonIdx + 1).trim(), mergedRevNums);
                }
            }
        }

        // 检查每个版本号是否在已合并集合中
        for (String rev : revisions) {
            try {
                long revNum = Long.parseLong(rev);
                if (mergedRevNums.contains(revNum)) {
                    merged.add(rev);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return merged;
    }

    /**
     * 从 SVN URL 中提取仓库内路径
     * 例如 https://svn.example.com/svn/repo/branches/feature -> /branches/feature
     */
    private static String extractRepoPath(String branchUrl) {
        // 常见的仓库根标识
        String[] markers = {"/trunk", "/branches/", "/tags/"};
        for (String marker : markers) {
            int idx = branchUrl.indexOf(marker);
            if (idx >= 0) {
                String path = branchUrl.substring(idx);
                // 去掉末尾斜杠
                if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
                return path;
            }
        }
        // 兜底：取最后两段路径
        String cleaned = branchUrl.replaceAll("/+$", "");
        int lastSlash = cleaned.lastIndexOf('/');
        if (lastSlash > 0) {
            int secondLast = cleaned.lastIndexOf('/', lastSlash - 1);
            if (secondLast >= 0) {
                return cleaned.substring(secondLast);
            }
        }
        return null;
    }

    /**
     * 解析 mergeinfo 范围字符串，如 "100-200,300,400-500*"
     */
    private static void parseMergeRanges(String rangeStr, Set<Long> result) {
        for (String part : rangeStr.split(",")) {
            String cleaned = part.trim().replace("*", ""); // 去掉非继承标记
            if (cleaned.isEmpty()) continue;
            try {
                if (cleaned.contains("-")) {
                    String[] bounds = cleaned.split("-", 2);
                    long start = Long.parseLong(bounds[0].trim());
                    long end = Long.parseLong(bounds[1].trim());
                    for (long i = start; i <= end; i++) {
                        result.add(i);
                    }
                } else {
                    result.add(Long.parseLong(cleaned));
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private Result execute(String workingDir, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) {
                pb.directory(new java.io.File(workingDir));
            }
            // 补充常见路径到 PATH，避免 IDEA 子进程 PATH 不完整
            Map<String, String> env = pb.environment();
            String path = env.getOrDefault("PATH", "");
            env.put("PATH", "/usr/local/bin:/opt/homebrew/bin:/usr/bin:" + path);
            // 强制 svn 使用 UTF-8 编码，避免中文转换失败
            env.put("LANG", "en_US.UTF-8");
            env.put("LC_ALL", "en_US.UTF-8");
            pb.redirectErrorStream(false);

            Process process = pb.start();

            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(-1, "", "命令执行超时（" + TIMEOUT_SECONDS + "秒）");
            }

            return new Result(process.exitValue(), stdout, stderr);
        } catch (IOException e) {
            return new Result(-1, "", "执行命令失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "命令被中断");
        }
    }

    private Result executeRealtime(String workingDir, Consumer<String> outputLineCb, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) {
                pb.directory(new java.io.File(workingDir));
            }
            Map<String, String> env = pb.environment();
            String path = env.getOrDefault("PATH", "");
            env.put("PATH", "/usr/local/bin:/opt/homebrew/bin:/usr/bin:" + path);
            env.put("LANG", "en_US.UTF-8");
            env.put("LC_ALL", "en_US.UTF-8");
            pb.redirectErrorStream(false);

            Process process = pb.start();
            java.nio.charset.Charset charset = resolveProcessCharset();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outReader = startStreamReader(process.getInputStream(), charset, stdout, outputLineCb);
            Thread errReader = startStreamReader(process.getErrorStream(), charset, stderr, outputLineCb);

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                joinReader(outReader);
                joinReader(errReader);
                return new Result(-1, stdout.toString(), "命令执行超时（" + TIMEOUT_SECONDS + "秒）");
            }

            joinReader(outReader);
            joinReader(errReader);
            return new Result(process.exitValue(), stdout.toString(), stderr.toString());
        } catch (IOException e) {
            return new Result(-1, "", "执行命令失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "命令被中断");
        }
    }

    private Thread startStreamReader(java.io.InputStream inputStream,
            java.nio.charset.Charset charset,
            StringBuilder collector,
            Consumer<String> outputLineCb) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (collector) {
                        if (collector.length() > 0) collector.append("\n");
                        collector.append(line);
                    }
                    if (outputLineCb != null) {
                        outputLineCb.accept(line);
                    }
                }
            } catch (IOException ignored) {
                // 流关闭或进程结束时可能抛异常，忽略即可
            }
        }, "svn-stream-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinReader(Thread thread) {
        if (thread == null) return;
        try {
            thread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String readStream(java.io.InputStream is) throws IOException {
        // 优先尝试 UTF-8，如果系统默认编码不同则回退
        java.nio.charset.Charset charset = resolveProcessCharset();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private java.nio.charset.Charset resolveProcessCharset() {
        java.nio.charset.Charset charset = StandardCharsets.UTF_8;
        try {
            String sysEnc = System.getProperty("sun.jnu.encoding", "");
            if (!sysEnc.isEmpty()) {
                charset = java.nio.charset.Charset.forName(sysEnc);
            }
        } catch (Exception ignored) {
        }
        return charset;
    }
}
