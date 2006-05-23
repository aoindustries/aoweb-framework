package com.aoindustries.website.framework;

/*
 * Copyright 2000-2006 by AO Industries, Inc.,
 * 2200 Dogwood Ct N, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.io.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import javax.servlet.http.*;

/**
 * @author  AO Industries, Inc.
 */
abstract public class TreePage extends WebPage {

    /**
     * The color of the lines.
     */
    public static final int TREE_LINE_COLOR=0xa8a8a8;

    /**
     * The parameter name to control the TreePage display mode.
     */
    public static final String TREEPAGE_MODE="treepage_mode";

    /**
     * Indicates the TreePage should automatically select the correct mode,
     * this is the default if the parameter is not provided.
     */
    public static final String MODE_AUTO="auto";
    
    /**
     * Indicates the TreePage should display its contents in a text-only format.
     */
    public static final String MODE_TEXT="text";
    
    /**
     * Indicates the TreePage should display its contents in an interactive format.
     */
    public static final String MODE_GUI="gui";

    /**
     * The width of the images.
     */
    private static final int IMAGE_WIDTH=24;
    
    /**
     * The height of the images.
     */
    private static final int IMAGE_HEIGHT=32;

    private static byte[] blank;
    private static final byte[][] jpgCache=new byte[9][];
    private static final byte[][] gifCache=new byte[9][];

    private static byte[] getImageBytes(int imageNum, boolean isSmooth) throws IOException {
        Profiler.startProfile(Profiler.IO, TreePage.class, "getImageBytes(int,boolean)", null);
        try {
            synchronized(jpgCache) {
                byte[] bytes;
                if(imageNum==0) {
                    // Load the blank image
                    bytes=blank;
                    if(bytes==null) {
			ByteArrayOutputStream bout=new ByteArrayOutputStream();
                        InputStream in=TreePage.class.getResourceAsStream("images/blank.gif");
			try {
			    byte[] buff=BufferManager.getBytes();
			    try {
				int ret;
				while((ret=in.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) bout.write(buff, 0, ret);
			    } finally {
				BufferManager.release(buff);
			    }
			} finally {
			    in.close();
			}
			bytes=blank=bout.toByteArray();
                    }
                } else {
                    byte[][] cache=isSmooth?jpgCache:gifCache;
                    bytes=cache[imageNum-1];
                    if(bytes==null) {
			ByteArrayOutputStream bout=new ByteArrayOutputStream();
                        InputStream in=TreePage.class.getResourceAsStream("images/tree_"+imageNum+"."+(isSmooth?"jpg":"gif"));
			try {
			    byte[] buff=BufferManager.getBytes();
			    try {
				int ret;
				while((ret=in.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) bout.write(buff, 0, ret);
			    } finally {
				BufferManager.release(buff);
			    }
			} finally {
                            in.close();
			}
			bytes=cache[imageNum-1]=bout.toByteArray();
                    }
                }
                return bytes;
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public TreePage() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, TreePage.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    public TreePage(WebSiteRequest req) {
        super(req);
        Profiler.startProfile(Profiler.INSTANTANEOUS, TreePage.class, "<init>(WebSiteRequest)", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    public TreePage(Object param) {
        super(param);
        Profiler.startProfile(Profiler.INSTANTANEOUS, TreePage.class, "<init>(Object)", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    public void doGet(WebSiteRequest req, HttpServletResponse resp) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, TreePage.class, "doGet(WebSiteRequest,HttpServletResponse)", null);
        try {
            String S=req.getParameter("image_num");
            if(S==null) super.doGet(req, resp);
            else {
                try {
                    int imageNum=Integer.parseInt(S);
                    if(imageNum<0 || imageNum>9) resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Unable to find image number "+imageNum);
                    else {
                        boolean useSmooth=useSmoothOutline(req);
                        resp.setContentType(imageNum==0?"image/gif":useSmooth?"image/jpeg":"image/gif");
                        byte[] bytes=getImageBytes(imageNum, useSmooth);
                        OutputStream out=resp.getOutputStream();
			try {
			    out.write(bytes);
			} finally {
			    out.flush();
			    out.close();
			}
                    }
                } catch(NumberFormatException err) {
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unable to parse image_num");
                }
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public long getSearchLastModified() throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, TreePage.class, "getSearchLastModified()", null);
        try {
            return getClassLastModified();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public long getLastModified(WebSiteRequest req) {
        Profiler.startProfile(Profiler.FAST, TreePage.class, "getLastModified(WebSiteRequest)", null);
        try {
            if(req==null) return -1;
            String S=req.getParameter("image_num");
            return S==null?-1:getClassLoaderUptime();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public void doGet(
        ChainWriter out,
        WebSiteRequest req,
        HttpServletResponse resp
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, TreePage.class, "doGet(ChainWriter,WebSiteRequest,HttpServletResponse)", null);
        try {
            List<TreePageData> tree = getTree(req);
            String mode;
            if(displayText(req)) {
                int treeLen = tree.size();

                // Break apart each line
                String[][] paths = getLines(tree);

                // Get the widest of the lines
                int longest=0;
                for (int c=0; c<paths.length; c++) {
                    String[] path = paths[c];
                    int width=0;
                    for (int d=0; d<path.length; d++) {
                        if(d>0) width+=3;
                        width+=path[d].length();
                    }
                    width+=3;
                    if(width>longest) longest=width;
                }

                out.print("<PRE>\n");

                String[] last = new String[0];
                for (int c = 0; c < treeLen; c++) {
                    int width = 0;
                    String[] path = paths[c];
                    int pathLen = path.length;
                    int max = Math.min(pathLen - 1, last.length);
                    int pos = 0;
                    for (; pos < max; pos++) {
                        if (last[pos].equals(path[pos])) {
                            if (pos > 0) {
                                boolean hasMore = false;
                                for (int d = c + 1; d < treeLen; d++) {
                                    int end = pos;
                                Loop :
                                    for (int e = 0; e < end; e++) {
                                        if (paths[d][e].equals(path[e])) {
                                            if (e == (end - 1) && !paths[d][end].equals(path[end])) {
                                                hasMore = true;
                                                break Loop;
                                            }
                                        } else break;
                                    }
                                }
                                out.print(hasMore ? "|  " : "   ");
                                width += 3;
                            }
                            int len2 = last[pos].length();
                            for (int d = 0; d < len2; d++)
                                out.print(' ');
                            width += len2;
                        } else break;
                    }
                    for (; pos < pathLen; pos++) {
                        String replaced=replaceHTML(path[pos]);
                        if (pos>0 && replaced.length()>0) {
                            out.print("+--");
                            width += 3;
                        }
                        String href;
                        if (pos == (path[pathLen-1].length()==0?(pathLen-2):(pathLen-1)) && (href = tree.get(c).getUrl()) != null) {
                            out.print("<A target='_top' href='");
                            out.print(href);
                            out.print("'>").print(replaced).print("</A>");
                        } else out.print(replaced);
                        String S;
                        if (replaced.length()>0 && (pos < (pathLen - 1) || ((S = tree.get(c).getPath()).length() > 0 && S.charAt(S.length() - 1) == '/')))
                            out.print('/');
                        else
                            out.print(' ');
                        width += replaced.length() + 1;
                    }
                    for (; width < longest; width++)
                        out.print(' ');
                    String description=tree.get(c).getDescription();
                    if(description!=null) out.println(description);

                    last = path;
                }
                out.print("</PRE>\n");
            } else handleRequest(out, req, resp, tree, -1, -1, null);
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    protected void doPost(
        WebSiteRequest req,
        HttpServletResponse resp
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, TreePage.class, "doPost(WebSiteRequest,HttpServletResponse)", null);
        try {
            List<TreePageData> tree = getTree(req);

            // Get the scroll to position
            int scrollToX = Integer.parseInt(req.getParameter("scroll_to_x"));
            int scrollToY = Integer.parseInt(req.getParameter("scroll_to_y"));

            ChainWriter out = getHTMLChainWriter(req, resp);
	    try {
		WebPageLayout layout = getWebPageLayout(req);
		layout.startHTML(
				 this,
				 req,
				 out,
				 scrollToX >= 0 ? ("window.scrollTo(" + scrollToX + ", " + scrollToY + ");") : null
				 );

		int treeLen = tree.size();
		boolean[] opened = new boolean[treeLen];
		for (int c = 0; c < treeLen; c++) {
		    opened[c] = "true".equals(req.getParameter("opened_" + c));
		}

		// Print the new table
		handleRequest(out, req, resp, tree, scrollToX, scrollToY, opened);

		layout.endHTML(this, req, out);
	    } finally {
		out.flush();
		out.close();
	    }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    /**
     * Gets the tree to be displayed.  Each row consists of three elements: path, href, description
     */
    abstract protected List<TreePageData> getTree(WebSiteRequest req) throws IOException, SQLException;

    /**
     * Handles the interactive form of this page.
     */
    private void handleRequest(
        ChainWriter out,
        WebSiteRequest req,
        HttpServletResponse resp,
        List<TreePageData> tree,
        int scrollToX,
        int scrollToY,
        boolean[] opened
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, TreePage.class, "handleRequest(ChainWriter,WebSiteRequest,HttpServletResponse,List<TreePageData>,int,int,boolean[])", null);
        try {
            WebPageLayout layout=getWebPageLayout(req);
            layout.startContent(out, req, 1, getPreferredContentWidth(req));
            layout.printContentTitle(out, req, this, 1);
            layout.printContentHorizontalDivider(out, req, 1, false);
            layout.startContentLine(out, req, 1, null);
            try {
                // Get the tree data
                int treeLen = tree.size();

                // Break apart each line
                String[][] paths = getLines(tree);

                if (opened == null) {
                    // Default to opened for first item
                    opened = new boolean[treeLen];
                    if (treeLen > 0) opened[0] = true;
                }

                // Write the javascript that controls the form
                out.print("<SCRIPT language='JavaScript'><!--\n"
                        + "  function openNode(index) {\n"
                        + "    eval('document.tree_form.opened_'+index+'.value=\"true\";');\n"
                        + "    document.tree_form.scroll_to_x.value=getPageXOffset(window);\n"
                        + "    document.tree_form.scroll_to_y.value=getPageYOffset(window);\n"
                        + "    document.tree_form.submit();\n"
                        + "  }\n"
                        + "\n"
                        + "  function closeNode(index) {\n"
                        + "    eval('document.tree_form.opened_'+index+'.value=\"false\";');\n"
                        + "    document.tree_form.scroll_to_x.value=getPageXOffset(window);\n"
                        + "    document.tree_form.scroll_to_y.value=getPageYOffset(window);\n"
                        + "    document.tree_form.submit();\n"
                        + "  }\n"
                        + "// --></SCRIPT>\n");

                // Write the form containing the current settings
                out.print("<FORM name='tree_form' method='POST'>\n");
                req.printFormFields(out, 1);
                out.print("  <INPUT type='hidden' name='scroll_to_x' value='").print(scrollToX).print("'>\n"
                        + "  <INPUT type='hidden' name='scroll_to_y' value='").print(scrollToY).print("'>\n");
                for(int c=0; c<treeLen; c++) {
                    out.print("  <INPUT type='hidden' name='opened_").print(c).print("' value='").print(opened[c]).print("'>\n");
                }

                // Display the tree in a table with links for opening/closing the different parts
                out.print("  <TABLE border=0 cellspacing=0 cellpadding=0>\n");

                String[] last = new String[0];
                for (int c = 0; c < treeLen; c++) {
                    String[] path = paths[c];
                    int pathLen = path.length;

                    // Every parent must be open for this to be visible
                    boolean visible = true;
                    Loop2 :
                        for (int d = 0; d < (pathLen - 1); d++) {
                            // Find the first row that has all the path up to current step
                            for (int e = 0; e < c; e++) {
                                String[] parentpath = paths[e];
                                if (parentpath.length > d) {
                                    boolean isParent = true;
                                    for (int f = 0; f <= d; f++) {
                                        if (!parentpath[f].equals(path[f])) {
                                            isParent = false;
                                            break;
                                        }
                                    }
                                    if (isParent) {
                                        if (!opened[e]) {
                                            visible = false;
                                            break Loop2;
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                        if (visible) {
                            out.print("    <TR>\n"
                                    + "      <TD nowrap><TABLE border=0 cellspacing=0 cellpadding=0><TR><TD nowrap>");

                            int max = Math.min(pathLen - 1, last.length);
                            int pos = 0;

                            // Skip the part of the path that is already displayed by the parent
                            for (; pos < max; pos++) {
                                if (last[pos].equals(path[pos])) {
                                    boolean hasMore = false;
                                    for (int d = c + 1; d < treeLen; d++) {
                                        int end = pos;
                                    Loop :
                                        for (int e = 0; e < end; e++) {
                                            if (paths[d][e].equals(path[e])) {
                                                if (e == (end - 1) && !paths[d][end].equals(path[end])) {
                                                    hasMore = true;
                                                    break Loop;
                                                }
                                            } else break;
                                        }
                                    }
                                    out.print("<IMG src='").printEI(req.getURL(this, "image_num="+(hasMore?1:0))).print("' border=0 width="+IMAGE_WIDTH+" height="+IMAGE_HEIGHT+">");
                                } else break;
                            }

                            // Display the remaining part of the path
                            for(; pos < pathLen; pos++) {
                                if(path[pos].length()>0) {
                                    // Determine has sub items
                                    boolean hasSub = false;
                                    if (c < (treeLen - 1)) {
                                        String[] next_path = paths[c + 1];
                                        if (next_path.length >= pathLen) {
                                            hasSub = true;
                                            for (int e = 0; e < pathLen; e++) {
                                                String tempPath=path[e];
                                                if(tempPath.length()>0 && !tempPath.equals(next_path[e])) {
                                                    hasSub = false;
                                                    break;
                                                }
                                            }
                                        }
                                    }

                                    // Determine if the line continues farther down
                                    boolean hasMore = false;
                                    for (int d = c; d < treeLen; d++) {
                                        int end = pos;
                                        Loop3 : for (int e = 0; e < end; e++) {
                                            if (paths[d][e].equals(path[e])) {
                                                if (e == (end - 1) && !paths[d][end].equals(path[end])) {
                                                    hasMore = true;
                                                    break Loop3;
                                                }
                                            } else break;
                                        }
                                    }

                                    if(hasSub) {
                                        out
                                            .print("<A href='javascript:")
                                            .print(opened[c] ? "closeNode(" : "openNode(")
                                            .print(c)
                                            .print(");'><IMG alt='").print(opened[c] ? "Close" : "Open")
                                            .print("' src='")
                                            .printEI(
                                                req.getURL(
                                                    this,
                                                    "image_num="+(
                                                        opened[c]
                                                        ? (hasMore ? 4 : (c > 0 ? 5 : 9))
                                                        : (hasMore ? 6 : (c > 0 ? 7 : 8))
                                                    )
                                                )
                                            ).print("' border=0 width="+IMAGE_WIDTH+" height="+IMAGE_HEIGHT+"></A>");
                                    } else {
                                        out
                                            .print("<IMG src='")
                                            .printEI(
                                                req.getURL(
                                                    this,
                                                    "image_num="+(hasMore ? 2 : 3)
                                                )
                                            ).print("' border=0 width="+IMAGE_WIDTH+" height="+IMAGE_HEIGHT+">");
                                    }
                                    out.print("<IMG src='").printEI(req.getURL(this, "image_num=0")).print("' border=0 width=4 height="+IMAGE_HEIGHT+"></TD><TD nowrap>");
                                }

                                boolean useCodeFont=useCodeFont(req);
                                if (useCodeFont) out.print("<CODE>");
                                String href;
                                if(
                                    (
                                        (pathLen>=2 && pos==(pathLen-2) && path[pathLen-1].length()==0)
                                        || (pos==(pathLen-1) && path[pathLen-1].length()>0)
                                    ) && (href = tree.get(c).getUrl()) != null
                                ) {
                                    out.print("<A class='ao_light_link' target='_top' href='").printEI(href).print("'>").print(path[pos]).print("</A>");
                                } else if(path[pos].length()>0) out.print(path[pos]);
                                if(useCodeFont) out.print("</CODE>");
                            }

                            out.print("</TD></TR></TABLE></TD>\n"
                                    + "      <TD nowrap width=20><IMG src='").printEI(req.getURL(this, "image_num=0")).print("' width=20 height=1></TD>\n"
                                    + "      <TD nowrap>");
                            String description=tree.get(c).getDescription();
                            if(description!=null) out.print(description);
                            out.print("</TD>\n"
                                    + "    </TR>\n");
                            last = path;
                        }
                }

                out.print("  </TABLE>\n"
                        + "</FORM>\n");
            } finally {
                layout.endContentLine(out, req, 1, false);
                layout.endContent(this, out, req, 1);
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    protected boolean useCodeFont(WebSiteRequest req) {
        Profiler.startProfile(Profiler.INSTANTANEOUS, TreePage.class, "useCodeFont(WebSiteRequest)", null);
        try {
            return false;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public boolean useFrames(WebSiteRequest req) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, TreePage.class, "useFrames(WebSiteRequest)", null);
        try {
            return !displayText(req);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
    
    private static String[][] getLines(List<TreePageData> tree) {
        Profiler.startProfile(Profiler.UNKNOWN, TreePage.class, "getLines(List<TreePageData>)", null);
        try {
            int treeLen = tree.size();
            String[][] paths=new String[treeLen][];
            for (int c = 0; c < treeLen; c++) {
                paths[c] = StringUtility.splitString(tree.get(c).getPath(), '/');
            }
            return paths;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    private boolean displayText(WebSiteRequest req) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, TreePage.class, "displayText(WebSiteRequest)", null);
        try {
            // A search being performed
            if(req==null) return true;

            WebPageLayout layout=getWebPageLayout(req);
            if(layout instanceof TextOnlyLayout) return true;

            String mode=req.getParameter(TREEPAGE_MODE);

            // Auto mode
            if(mode==null || MODE_AUTO.equals(mode)) return req.isSearchEngine() || req.isLynx();

            // Text mode
            if(MODE_TEXT.equals(mode)) return true;

            // Default to gui mode
            return false;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
    
    abstract public boolean useSmoothOutline(WebSiteRequest req);
    
    public static String replaceHTML(String S) {
        Profiler.startProfile(Profiler.FAST, TreePage.class, "replaceHTML(String)", null);
        try {
            return StringUtility.replace(S, "&#047;", "/");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}