package com.indeed.imhotep.web;

import com.indeed.imhotep.sql.ast2.FromClause;
import com.indeed.imhotep.sql.ast2.QueryParts;
import com.indeed.imhotep.sql.parser.QuerySplitter;
import com.indeed.imhotep.sql.parser.StatementParser;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ObjectNode;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * @author vladimir
 */
@Controller
public class SplitterServlet {
    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss");

    @RequestMapping("/split")
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp, @RequestParam("q") String query) throws ServletException, IOException {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setContentType("application/json");
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode json = mapper.createObjectNode();
        QueryParts parts = null;
        try {
            parts = QuerySplitter.splitQuery(query);
        } catch (Exception e) {
            json.put("error", e.toString());
        }
        if(parts != null) {
            json.put("from", parts.from);
            json.put("where", parts.where);
            json.put("groupBy", parts.groupBy);
            json.put("select", parts.select);
            json.put("limit", parts.limit);

            FromClause fromClause = null;
            try {
                fromClause = StatementParser.parseFromClause(parts.from);
            } catch (Exception ignored) { }
            json.put("dataset", fromClause != null ? fromClause.getDataset() : "");
            json.put("start", fromClause != null ? fromClause.getStart().toString(dateTimeFormatter) : "");
            json.put("end", fromClause != null ? fromClause.getEnd().toString(dateTimeFormatter) : "");
            json.put("startRawString", fromClause != null ? fromClause.getStartRawString() : "");
            json.put("endRawString", fromClause != null ? fromClause.getEndRawString() : "");
        }

        final ServletOutputStream outputStream = resp.getOutputStream();
        mapper.writeValue(outputStream, json);
        outputStream.close();
    }
}
