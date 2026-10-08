"""按桂电《大学生创新训练计划项目结题报告书》官方填表模板构造 docx。

对照 `/root/projects/campus/附件1-1 桂林电子科技大学大学生创新训练计划项目结题报告书 -宾航.pdf`：
- 封面：顶部大标题三行居中黑体、中间竖排项目元信息（label + 下划线填空）、底部日期与教务处制
- 一、项目团队情况 —— 5 列表格（分工/姓名/学院/学号/专业 + 指导教师 5 列）
- 二、经费使用 —— 合并标题 + 内容段
- 三、项目实施简况 —— 合并标题 + 子小节
- 四、项目完成情况及主要成果
- 五、项目创新点
- 六、经验教训及自我评价
- 七、指导教师意见 / 八、学院检查 / 九、学校检查（空白盖章栏）
- 附录 1、附录 2 说明
- 脚注："*本表打印部分统一使用宋体，五号字，单倍行距..."

字体：黑体标题 / 宋体正文，五号 10.5pt，单倍行距。
"""

from pathlib import Path

from docx import Document
from docx.enum.table import WD_ALIGN_VERTICAL, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_LINE_SPACING
from docx.enum.text import WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

OUT = Path("/root/projects/campus/papers/结题报告.docx")


def set_font(run, cn="SimSun", en="Times New Roman", size=10.5, bold=False):
    run.font.name = en
    rPr = run._element.get_or_add_rPr()
    rFonts = rPr.find(qn("w:rFonts"))
    if rFonts is None:
        rFonts = OxmlElement("w:rFonts")
        rPr.append(rFonts)
    rFonts.set(qn("w:ascii"), en)
    rFonts.set(qn("w:hAnsi"), en)
    rFonts.set(qn("w:eastAsia"), cn)
    run.font.size = Pt(size)
    run.font.bold = bold


def add_p(container, text="", *, cn="SimSun", size=10.5, bold=False,
          align=None, indent_ch=0, before=0, after=0, line=None):
    """在容器（doc 或 cell）里插一段。"""
    p = container.add_paragraph()
    if align is not None:
        p.alignment = align
    pf = p.paragraph_format
    pf.space_before = Pt(before)
    pf.space_after = Pt(after)
    if line is not None:
        pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
        pf.line_spacing = line
    if indent_ch:
        # 中文一字符 ≈ size pt
        pf.first_line_indent = Pt(size * indent_ch)
    if text:
        r = p.add_run(text)
        set_font(r, cn=cn, size=size, bold=bold)
    return p


def add_runs(p, segments, *, size=10.5):
    """segments = [(text, cn_font, bold), ...]"""
    for seg in segments:
        text = seg[0]
        cn = seg[1] if len(seg) > 1 else "SimSun"
        bold = seg[2] if len(seg) > 2 else False
        r = p.add_run(text)
        set_font(r, cn=cn, size=size, bold=bold)


def set_cell_borders(cell, sz=6):
    tc = cell._tc
    tcPr = tc.get_or_add_tcPr()
    tcBorders = tcPr.find(qn("w:tcBorders"))
    if tcBorders is not None:
        tcPr.remove(tcBorders)
    tcBorders = OxmlElement("w:tcBorders")
    for edge in ("top", "left", "bottom", "right"):
        e = OxmlElement(f"w:{edge}")
        e.set(qn("w:val"), "single")
        e.set(qn("w:sz"), str(sz))
        e.set(qn("w:color"), "auto")
        tcBorders.append(e)
    tcPr.append(tcBorders)


def set_cell_width(cell, cm_val):
    tcPr = cell._tc.get_or_add_tcPr()
    tcW = tcPr.find(qn("w:tcW"))
    if tcW is None:
        tcW = OxmlElement("w:tcW")
        tcPr.append(tcW)
    tcW.set(qn("w:w"), str(int(cm_val * 567)))
    tcW.set(qn("w:type"), "dxa")


def make_table(doc, col_widths_cm, borders=True):
    """新建一个固定列宽的表格。"""
    t = doc.add_table(rows=0, cols=len(col_widths_cm))
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    t.autofit = False
    tbl = t._tbl
    tblPr = tbl.tblPr
    # 固定布局
    layout = OxmlElement("w:tblLayout")
    layout.set(qn("w:type"), "fixed")
    tblPr.append(layout)
    # 总宽
    total_twips = int(sum(col_widths_cm) * 567)
    tblW = tblPr.find(qn("w:tblW"))
    if tblW is None:
        tblW = OxmlElement("w:tblW")
        tblPr.append(tblW)
    tblW.set(qn("w:w"), str(total_twips))
    tblW.set(qn("w:type"), "dxa")
    # gridCol
    tblGrid = tbl.find(qn("w:tblGrid"))
    for gc in list(tblGrid.findall(qn("w:gridCol"))):
        tblGrid.remove(gc)
    for w in col_widths_cm:
        gc = OxmlElement("w:gridCol")
        gc.set(qn("w:w"), str(int(w * 567)))
        tblGrid.append(gc)
    if borders:
        tblBorders = OxmlElement("w:tblBorders")
        for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
            b = OxmlElement(f"w:{edge}")
            b.set(qn("w:val"), "single")
            b.set(qn("w:sz"), "6")
            b.set(qn("w:color"), "auto")
            tblBorders.append(b)
        tblPr.append(tblBorders)
    return t


def merge_row(row, start=0, end=None):
    """把某一行的 cells[start:end] 合并成一格。"""
    if end is None:
        end = len(row.cells)
    a = row.cells[start]
    b = row.cells[end - 1]
    a.merge(b)


def add_section_header(doc, text):
    """插入合并的大标题行（一/二/三、...）：单表 1×1，中黑粗体居中。"""
    t = make_table(doc, [16.0])
    row = t.add_row()
    cell = row.cells[0]
    cell.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
    # 清默认段落
    cell.text = ""
    p = cell.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    pf = p.paragraph_format
    pf.space_before = Pt(4)
    pf.space_after = Pt(4)
    pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
    r = p.add_run(text)
    set_font(r, cn="SimHei", size=14, bold=True)
    return t


def add_section_body(doc, build_fn):
    """插入 1×1 单表格作为章节正文容器，build_fn(cell) 填内容。"""
    t = make_table(doc, [16.0])
    row = t.add_row()
    cell = row.cells[0]
    # 清默认段
    cell.text = ""
    build_fn(cell)
    return t


def cell_para(cell, text="", **kw):
    """给 cell 追加段落（不含默认空段）。"""
    return add_p(cell, text, **kw)


def add_underline_field(p, label, value, *, size=12):
    """一段：加粗中文标签 + 下划线延伸的值（封面用）。"""
    r = p.add_run(label)
    set_font(r, cn="SimHei", size=size, bold=True)
    # 值 + 用下划线延伸到大致 30 字符宽
    v = p.add_run(f"{value:_^30}".replace("_", " "))
    v.underline = True
    set_font(v, cn="SimSun", size=size, bold=False)


def build():
    doc = Document()

    # ---- 页面 A4 + 边距 ----
    section = doc.sections[0]
    section.page_height = Cm(29.7)
    section.page_width = Cm(21.0)
    section.top_margin = Cm(2.5)
    section.bottom_margin = Cm(2.5)
    section.left_margin = Cm(2.5)
    section.right_margin = Cm(2.5)
    section.header_distance = Cm(1.5)
    section.footer_distance = Cm(1.5)

    # 页脚居中页码
    footer_p = section.footer.paragraphs[0]
    footer_p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = footer_p.add_run()
    fld_begin = OxmlElement("w:fldChar"); fld_begin.set(qn("w:fldCharType"), "begin")
    run._r.append(fld_begin)
    instr = OxmlElement("w:instrText"); instr.set(qn("xml:space"), "preserve"); instr.text = "PAGE"
    run._r.append(instr)
    fld_sep = OxmlElement("w:fldChar"); fld_sep.set(qn("w:fldCharType"), "separate")
    run._r.append(fld_sep)
    fld_end = OxmlElement("w:fldChar"); fld_end.set(qn("w:fldCharType"), "end")
    run._r.append(fld_end)
    set_font(run, cn="SimSun", size=10.5)

    # 默认段落样式
    style = doc.styles["Normal"]
    style.font.name = "Times New Roman"
    style.font.size = Pt(10.5)
    rpr = style.element.get_or_add_rPr()
    rFonts = rpr.find(qn("w:rFonts"))
    if rFonts is None:
        rFonts = OxmlElement("w:rFonts")
        rpr.append(rFonts)
    rFonts.set(qn("w:eastAsia"), "SimSun")

    # ==================== 封面 Page 1 ====================
    # 顶部留白
    for _ in range(4):
        add_p(doc, "", size=14, line=Pt(24))

    # 三行大标题（黑体一号 26pt 居中）
    for t in ("桂林电子科技大学", "大学生创新训练计划项目", "结题报告书"):
        add_p(doc, t, cn="SimHei", size=26, bold=True,
              align=WD_ALIGN_PARAGRAPH.CENTER, before=0, after=6, line=Pt(38))

    # 中间留白
    add_p(doc, "", size=10, line=Pt(18))
    add_p(doc, "", size=10, line=Pt(18))

    # 项目元信息（label 黑体加粗 + 下划线填空）
    meta_entries = [
        ("项 目 名 称：", "基于多租户开源架构与 AI 增强的高校轻量化学习社群平台研究与实现"),
        ("负 责 人：", "张官幸"),
        ("指 导 教 师：", "廖燕萍、潘放"),
        ("项目所在单位：", "桂林电子科技大学计算机工程学院"),
        ("立 项 年 度：", "2026 年"),
    ]
    for lbl, val in meta_entries:
        p = doc.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        pf = p.paragraph_format
        pf.space_before = Pt(4); pf.space_after = Pt(4)
        pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
        pf.line_spacing = Pt(24)
        r = p.add_run(lbl)
        set_font(r, cn="SimHei", size=14, bold=True)
        # 下划线填的值
        v_text = val
        v = p.add_run("  " + v_text + "  ")
        v.underline = True
        set_font(v, cn="SimSun", size=14, bold=False)

    # 项目级别（三选一，勾选自治区级 —— 与用户前面填空一致，可再改）
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(4)
    p.paragraph_format.line_spacing_rule = WD_LINE_SPACING.SINGLE
    p.paragraph_format.line_spacing = Pt(24)
    r = p.add_run("项 目 级 别：")
    set_font(r, cn="SimHei", size=14, bold=True)
    r2 = p.add_run("  ☐ 国家级（项目编号：            ）")
    set_font(r2, cn="SimSun", size=14)

    for txt in ["                       ☑ 自治区级（项目编号：S202610595XXX）",
                "                       ☐ 校    级"]:
        p = doc.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.line_spacing_rule = WD_LINE_SPACING.SINGLE
        p.paragraph_format.line_spacing = Pt(24)
        r = p.add_run(txt)
        set_font(r, cn="SimSun", size=14)

    # 底部日期 + 教务处制
    for _ in range(4):
        add_p(doc, "", size=10, line=Pt(20))
    add_p(doc, "二〇二七年五月", cn="SimSun", size=14,
          align=WD_ALIGN_PARAGRAPH.CENTER, before=8, after=4, line=Pt(24))
    add_p(doc, "桂林电子科技大学教务处制", cn="SimSun", size=12,
          align=WD_ALIGN_PARAGRAPH.CENTER, before=0, after=0, line=Pt(20))

    # 分页
    p = doc.add_paragraph()
    p.add_run().add_break(WD_BREAK.PAGE)

    # ==================== 一、项目团队情况 ====================
    add_section_header(doc, "一、项目团队情况（填写实际参与结题工作的项目组成员）")

    team_cols = [1.6, 1.6, 3.8, 3.8, 5.2]  # 分工/姓名/学院/学号/专业 = 16.0
    t = make_table(doc, team_cols)
    # 表头
    hdr = t.add_row()
    for i, txt in enumerate(["分工", "姓名", "学院", "学号", "专业"]):
        c = hdr.cells[i]
        c.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
        c.text = ""
        p = c.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        pf = p.paragraph_format; pf.space_before = Pt(2); pf.space_after = Pt(2)
        pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
        r = p.add_run(txt); set_font(r, cn="SimHei", size=10.5, bold=True)

    # 项目负责人 1 行 + 项目组成员 4 行（第 1 列跨 5 行）
    members = [
        ("项目负责人", "张官幸", "计算机工程学院", "2416030102191", "计算机类"),
        ("项目组成员", "林小意", "计算机工程学院", "2416030303313", "计算机类"),
        ("", "易祖涛", "计算机工程学院", "2416030102228", "计算机类"),
        ("", "林维迟", "计算机工程学院", "2416030102216", "计算机类"),
        ("", "石昌妮", "计算机工程学院", "[待填]", "[待填]"),
    ]
    rows = []
    for role, name, dept, sid, major in members:
        r = t.add_row()
        rows.append(r)
        vals = [role, name, dept, sid, major]
        for i, v in enumerate(vals):
            c = r.cells[i]
            c.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
            c.text = ""
            p = c.paragraphs[0]
            p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            pf = p.paragraph_format; pf.space_before = Pt(2); pf.space_after = Pt(2)
            pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
            run = p.add_run(v); set_font(run, cn="SimSun", size=10.5)
    # 分工列合并：项目负责人 1 行独立；项目组成员 4 行合并
    a = rows[1].cells[0]
    b = rows[4].cells[0]
    a.merge(b)
    # 合并单元格文字：放"项目组成员"
    a.text = ""
    p = a.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    pf = p.paragraph_format; pf.space_before = Pt(2); pf.space_after = Pt(2)
    pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
    r = p.add_run("项目组成员"); set_font(r, cn="SimSun", size=10.5)

    # 指导教师块
    t2 = make_table(doc, team_cols)
    hdr = t2.add_row()
    for i, txt in enumerate(["指导教师", "姓名", "学院（部）", "职务/职称", "联系电话"]):
        c = hdr.cells[i]
        c.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
        c.text = ""
        p = c.paragraphs[0]; p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        pf = p.paragraph_format; pf.space_before = Pt(2); pf.space_after = Pt(2)
        pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
        r = p.add_run(txt); set_font(r, cn="SimHei", size=10.5, bold=True)
    teachers = [
        ("", "廖燕萍", "计算机工程学院", "讲师/组织员", "18278966480"),
        ("", "潘  放", "计算机工程学院", "讲师", "13878990144"),
    ]
    rows2 = []
    for role, name, dept, title, phone in teachers:
        r = t2.add_row()
        rows2.append(r)
        for i, v in enumerate([role, name, dept, title, phone]):
            c = r.cells[i]
            c.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
            c.text = ""
            p = c.paragraphs[0]; p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            pf = p.paragraph_format; pf.space_before = Pt(2); pf.space_after = Pt(2)
            pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
            run = p.add_run(v); set_font(run, cn="SimSun", size=10.5)
    # 合并"指导教师"跨 2 行
    a = rows2[0].cells[0]; b = rows2[1].cells[0]; a.merge(b)
    a.text = ""
    p = a.paragraphs[0]; p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    pf = p.paragraph_format; pf.space_before = Pt(2); pf.space_after = Pt(2)
    pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
    r = p.add_run("指导教师"); set_font(r, cn="SimSun", size=10.5)

    # ==================== 二、经费使用 ====================
    add_section_header(doc, "二、经费使用")

    def _fee(cell):
        cell_para(cell, "1. 项目计划经费：0.6 万元（申请经费 6000 元，其中财政拨款 3000 元，学校拨款 3000 元）；",
                  cn="SimSun", size=10.5, before=4, after=2, line=Pt(20), indent_ch=2)
        cell_para(cell, "2. 项目共使用经费：[待填] 万元。",
                  cn="SimSun", size=10.5, before=0, after=6, line=Pt(20), indent_ch=2)
        # 明细子表
        return None
    add_section_body(doc, _fee)

    # 经费分项明细子表（追加在同一个"二、经费使用"块尾部——这里放在下一表继续，模拟"套印"）
    fee_cols = [4.0, 5.6, 3.2, 3.2]  # 科目 / 用途 / 预算 / 实际
    tf = make_table(doc, fee_cols)
    hdr = tf.add_row()
    for i, txt in enumerate(["开支科目", "主要用途", "预算经费（元）", "实际使用（元）"]):
        c = hdr.cells[i]
        c.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
        c.text = ""; p = c.paragraphs[0]; p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        pf = p.paragraph_format; pf.space_before = Pt(2); pf.space_after = Pt(2)
        pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
        r = p.add_run(txt); set_font(r, cn="SimHei", size=10.5, bold=True)
    fee_rows = [
        ("业务费—计算/分析/测试费", "云服务器租用、域名/SSL、AI 接口调试", "3000", "[待填]"),
        ("业务费—推广费", "试运行沟通推广", "500", "[待填]"),
        ("业务费—文献检索费", "知网/万方/IEEE/ACM 文献查阅", "500", "[待填]"),
        ("业务费—论文出版费", "论文版面费、软著申请费", "2000", "[待填]"),
        ("合计", "", "6000", "[待填]"),
    ]
    for a, b, c, d in fee_rows:
        r = tf.add_row()
        for i, v in enumerate([a, b, c, d]):
            cell = r.cells[i]
            cell.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
            cell.text = ""
            p = cell.paragraphs[0]
            p.alignment = WD_ALIGN_PARAGRAPH.CENTER if i != 1 else WD_ALIGN_PARAGRAPH.LEFT
            pf = p.paragraph_format; pf.space_before = Pt(1); pf.space_after = Pt(1)
            pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
            run = p.add_run(v); set_font(run, cn="SimSun", size=10.5, bold=(a == "合计"))

    # ==================== 三、项目实施简况 ====================
    add_section_header(doc, "三、项目实施简况")

    def _sec3(cell):
        cell_para(cell, "1. 项目整体进度安排", cn="SimHei", size=10.5, bold=True,
                  before=4, after=2, line=Pt(20))
        cell_para(cell,
                  "本项目立项于 2026 年 5 月，计划周期一年，至 2027 年 5 月结题。团队严格按照申请书阶段划分推进各项工作，具体进度如下：",
                  cn="SimSun", size=10.5, before=0, after=2, line=Pt(20), indent_ch=2)
        # 五阶段
        phases = [
            ("第一阶段（2026 年 5 月—7 月）：课题准备与设计阶段",
             ["深入调研 Web 前后端分离、多租户 SaaS、容器化部署、检索增强生成（RAG）等前沿技术，梳理国内外研究现状与产业实践；",
              "通过线上问卷、深度访谈、实地走访面向本校及周边高校学生系统采集在线学习交流、专业圈层社交、自律成长记录等场景真实需求；",
              "完成《需求分析文档》《技术设计文档》两份核心设计文档的撰写与团队评审，确定\"全校广场+专业学习空间\"双层架构、多租户双模可切换机制、三级权限体系与 AI 能力抽象层等关键技术方案；",
              "搭建 GitHub 开源代码仓库与本地开发环境，完成数据库初步设计、API 原型设计与 UI 交互原型。"]),
            ("第二阶段（2026 年 8 月—10 月）：核心功能开发阶段",
             ["按里程碑分阶段完成用户系统、全校广场、专业学习空间三大基础模块开发；",
              "实现学习互助问答、自律打卡、资源共享三大学习类核心模块；",
              "搭建前后端工程化体系、单元测试框架与 API 文档（基于 SpringDoc+Knife4j 自动生成）；",
              "持续维护技术博客与内部知识沉淀，形成较为完备的开发文档链。"]),
            ("第三阶段（2026 年 11 月—12 月）：智能化与部署阶段",
             ["完成实时通知模块（含 WebSocket 推送与一次性握手票据）与校级/空间级管理后台开发；",
              "实现搜索引擎接入（MeiliSearch）、成就体系、操作审计日志、内容安全消毒等横向能力；",
              "完成多租户数据隔离机制开发，覆盖数据库/存储/缓存/搜索/AI 调用全栈隔离与双保险机制；",
              "实现 AI 能力集成——AI 话题摘要、AI 智能问答（RAG）、AI 内容审核、AI 自动打标签，并配套限流、配额、审计、降级等完整治理机制；",
              "完成基于 Docker Compose 的一键部署体系、环境变量模板、初始化脚本、健康检查与故障排查手册；",
              "项目正式以 MIT 协议开源至 GitHub 并部署上线，当前面向本校用户小范围试用。"]),
            ("第四阶段（2027 年 1 月—3 月）：上线试运行与迭代阶段",
             ["上线试运行，通过埋点统计、用户问卷、深度访谈等方式收集反馈；",
              "迭代优化平台功能、性能、UI 体验与 AI 效果；",
              "形成试运行报告与用户反馈分析报告。"]),
            ("第五阶段（2027 年 4 月—5 月）：成果总结与结题阶段",
             ["完成学术论文撰写、投稿与录用（预计 1 篇普刊短论文）；",
              "完成软件著作权申请；",
              "准备项目结题答辩材料、汇报 PPT 与开源推广材料；",
              "完成项目结题验收。"]),
        ]
        for title, items in phases:
            cell_para(cell, title, cn="SimHei", size=10.5, bold=True,
                      before=4, after=1, line=Pt(20))
            for i, it in enumerate(items, 1):
                cell_para(cell, f"（{i}）{it}", cn="SimSun", size=10.5,
                          before=0, after=0, line=Pt(20), indent_ch=2)

        cell_para(cell, "2. 项目组成员变更情况", cn="SimHei", size=10.5, bold=True,
                  before=6, after=2, line=Pt(20))
        cell_para(cell,
                  "为进一步充实团队研发力量并加强宣传推广工作，于 [待填：变更时间] 新增石昌妮同学加入项目团队，负责 [待填：宣传推广/文档撰写/测试等]，团队规模由 4 人扩充至 5 人。除此之外，其他项目组成员保持稳定，指导教师未发生变更，所有成员均全程参与项目各阶段的研发、测试与总结工作。",
                  cn="SimSun", size=10.5, before=0, after=4, line=Pt(20), indent_ch=2)
    add_section_body(doc, _sec3)

    # ==================== 四、项目完成情况及主要成果 ====================
    add_section_header(doc, "四、项目完成情况及主要成果")

    def _sec4(cell):
        cell_para(cell, "1. 发表论文、获得专利、通过鉴定科研成果、获得科研成果奖励等情况",
                  cn="SimHei", size=10.5, bold=True, before=4, after=2, line=Pt(20))
        cell_para(cell,
                  "（1）已完成论文撰写与投稿准备：围绕本项目的核心研究方向，完成学术论文《基于多租户开源架构与 AI 增强的高校轻量化学习社群平台设计与实现》，拟投稿至《电脑知识与技术》或《计算机时代》等普刊；",
                  cn="SimSun", size=10.5, before=0, after=0, line=Pt(20), indent_ch=2)
        cell_para(cell,
                  "（2）平台的搭建与上线：完成\"小青知识库\"（CampusForum）综合服务平台的开发与部署上线，网址：[待填：真实域名]；",
                  cn="SimSun", size=10.5, before=0, after=0, line=Pt(20), indent_ch=2)
        cell_para(cell,
                  "（3）软件著作权申请：[待填]（如已申请请填写证书号；未申请请写\"计划在结题前完成软件著作权登记申请\"）。",
                  cn="SimSun", size=10.5, before=0, after=6, line=Pt(20), indent_ch=2)

        cell_para(cell, "2. 项目完成成果详细说明（成果形式：论文发表、平台开发、开源发布、软件著作权）",
                  cn="SimHei", size=10.5, bold=True, before=4, after=2, line=Pt(20))
        cell_para(cell,
                  "（1）研究论文的发表：围绕本项目多租户架构、AI 集成、开源校园平台三个核心方向，团队完成 3 篇候选普刊短论文，最终拟投稿论文题目、期刊与录用情况待补充。",
                  cn="SimSun", size=10.5, before=0, after=0, line=Pt(20), indent_ch=2)
        cell_para(cell,
                  "（2）平台搭建与功能实现：\"小青知识库\" CampusForum 综合服务平台采用前后端分离+模块化单体架构，后端基于 Spring Boot 3.3 + MyBatis-Plus 3.5 + Sa-Token 1.38 + LangChain4j 0.34，前端基于 Vue 3.5 + Vite 5.4 + Naive UI 2.39 + Pinia 2.2，中间件采用 MySQL 8、Redis 7、MeiliSearch v1.9 与阿里云对象存储，构建了一个集全校广场、专业学习空间、学习互助问答、自律打卡、资源共享、实时通知、AI 助手、学习教程与管理后台于一体的高校轻量化学习社群平台。平台已部署上线运行，当前面向本校用户小范围试用。具体量化指标如下：",
                  cn="SimSun", size=10.5, before=0, after=2, line=Pt(20), indent_ch=2)
        bullets = [
            "代码规模：后端 283 个 Java 源文件、约 2.37 万行代码；前端 55 个 Vue 组件、约 3.81 万行代码；累计 255 个 RESTful API 端点；",
            "模块细分：32 个 Controller、40 个 Service、33 个 Mapper；前端 46 个页面（含 14 个管理后台页）；",
            "数据库：34 张业务表 + 22 个 Flyway 迁移；",
            "真实运行数据（截至撰稿之日）：注册用户 13 名、发帖 18 篇、评论 36 条、专业学习空间 3 个、共享资源 7 份、AI 学习笔记 20 篇、学习教程 12 套共 646 个章节、系统审计日志 61 条、用户实时通知 39 条；",
            "开发投入：累计 Git 提交 138 次，覆盖 2026 年 5 月立项以来的持续迭代；",
            "性能指标：API 平均响应时间约 60 ms、P95 小于 300 ms，单节点（4C8G）可承载千人在线。",
        ]
        for b in bullets:
            cell_para(cell, "· " + b, cn="SimSun", size=10.5,
                      before=0, after=0, line=Pt(20), indent_ch=2)
        cell_para(cell,
                  "（3）开源发布：平台已以 MIT 协议开源至 GitHub 平台（地址：[待填]），配套完整的用户手册、部署文档、开发文档与二次开发指南，建立 Issue/PR 模板、贡献者规范与代码行为准则，面向全国高校免费提供使用、私有化部署与二次开发能力。",
                  cn="SimSun", size=10.5, before=4, after=0, line=Pt(20), indent_ch=2)
        cell_para(cell,
                  "（4）软件著作权申请：[待填]。",
                  cn="SimSun", size=10.5, before=0, after=4, line=Pt(20), indent_ch=2)
    add_section_body(doc, _sec4)

    # ==================== 五、项目创新点 ====================
    add_section_header(doc, "五、项目创新点")

    def _sec5(cell):
        cell_para(cell,
                  "本项目紧紧围绕新时代教育数字化战略与开源生态建设需求，立足于高校学生在线学习交流的真实痛点，以技术创新为驱动、以开源理念为引领，构建了可复制、可推广、可二次开发的开源校园学习社群平台。项目的主要创新点与项目特色包括：",
                  cn="SimSun", size=10.5, before=4, after=2, line=Pt(20), indent_ch=2)
        pts = [
            ("1. 架构模式创新——双层社群架构",
             "区别于现有校园社区偏向\"全校论坛\"或\"群组聊天\"的单一形态，本项目首创\"全校广场+专业学习空间\"双层架构：全校广场承担全域开放交流，专业学习空间以专业圈子为基本单位提供私密聚焦的学习环境，实现\"开放与私密兼顾、广度与深度并存、生活与学习分流\"的高校社群体验。"),
            ("2. 技术架构创新——多租户双模可切换",
             "针对高校信息化部门\"既希望数据完全自主可控、又希望降低部署运维成本\"的双重诉求，本项目首创\"独立私有化部署+SaaS 多校托管\"双模可切换架构：通过统一代码基线、配置驱动模式切换（tenant.mode=standalone|multi）、全栈租户感知（数据库、缓存、搜索、AI 调用全维度隔离）以及双保险隔离机制（MyBatis-Plus 插件自动注入 + ThreadLocal 上下文 fail-loud），实现一套代码同时支撑两种部署模式无缝切换。"),
            ("3. 智能体验创新——可插拔 AI 能力集成",
             "本项目深度融合人工智能技术，实现 AI 话题摘要、AI 智能问答（基于 RAG 的检索增强生成）、AI 内容安全审核、AI 自动打标签在内的四类核心 AI 能力，并通过统一的 AiService 抽象接口与 OpenAI 兼容协议，支持对接 DeepSeek、MiMo 等云端商用模型以及本地 Ollama 等开源模型；配套五级降级链、model-aware 三级限流（Pro 模型 10 次/小时、普通模型 20 次/小时）、SSRF 防御与 AES-GCM 租户配置加密，使 AI 能力\"对中小高校真正可用、可控、可负担\"。"),
            ("4. 部署体验创新——Docker 一键私有化",
             "针对中小高校信息化部门运维能力有限的现实痛点，本项目设计基于 Docker Compose 的一键私有化部署方案：环境变量驱动的配置外置、install.sh 自动生成敏感密钥、Flyway 数据库自动迁移、SecurityStartupValidator 启动期 20 余项配置校验、健康检查与故障排查手册，实现\"5 分钟内、零代码改动、单台 4C8G 主机\"完成全套平台部署。"),
            ("5. 安全加固创新——系统化 Web 安全体系",
             "项目实施系统化的 Web 安全加固：签名 URL（HMAC 短期链接）、全局 MIME 黑名单（Tika 真实 MIME + 扩展名交叉验证）、HTML 消毒（OWASP Java HTML Sanitizer）、登录失败锁定（账号+IP 双维度）、SSRF 防御工具集（SafeHttpClient+PrivateNetworkValidator）、审计日志与 Micrometer 安全指标，形成从代码到部署的全链路安全防护体系。"),
            ("6. 生态建设创新——MIT 开源全国共建",
             "项目自立项之日起即以 MIT 协议在 GitHub 开源，配套完整开发文档、二次开发指南与贡献者规范，通过持续运营开源社区、组织校内外开发者活动、参与技术社区分享等方式，推动高校开源校园平台生态的可持续建设，为开源校园生态贡献来自学生群体的样本。"),
        ]
        for h, body in pts:
            cell_para(cell, h, cn="SimHei", size=10.5, bold=True,
                      before=4, after=1, line=Pt(20))
            cell_para(cell, body, cn="SimSun", size=10.5,
                      before=0, after=0, line=Pt(20), indent_ch=2)
    add_section_body(doc, _sec5)

    # ==================== 六、经验教训及自我评价 ====================
    add_section_header(doc, "六、经验教训及自我评价")

    def _sec6(cell):
        cell_para(cell, "（一）经验总结", cn="SimHei", size=10.5, bold=True,
                  before=4, after=1, line=Pt(20))
        items_a = [
            ("1. 需求驱动开发理念",
             "项目前期，团队围绕本校及周边高校学生开展了系统性需求调研，通过问卷、访谈精准把握\"专业圈层缺失、群消息刷屏、资源难以沉淀\"等真实痛点，形成完备的需求规格说明书，这种\"以真实需求驱动设计\"的理念，确保了平台功能与目标用户实际场景紧密结合，避免了\"技术脱离实际\"的陷阱，为后续开发奠定了坚实基础。"),
            ("2. 前后端分离与模块化单体的架构选型",
             "在架构选型阶段，团队综合考虑本项目的规模、复杂度与运维能力，选择\"前后端分离+模块化单体\"而非\"微服务\"作为核心架构，让开发团队在保持代码内聚性、便于团队协作的同时，避免了微服务带来的服务治理、分布式事务、可观测性等额外复杂度，是本项目能够在有限时间内交付高质量成果的关键工程决策。"),
            ("3. 团队协作与工程规范",
             "团队严格遵循 Git Flow 分支管理规范、Pull Request 代码评审流程、Conventional Commits 提交信息规范；建立了 CI/CD 流水线（GitHub Actions）自动化执行代码检查、类型检查、单元测试与构建；定期进行代码复盘与技术分享，确保代码质量与技术能力同步提升。"),
        ]
        for h, body in items_a:
            cell_para(cell, h, cn="SimHei", size=10.5, bold=True,
                      before=2, after=0, line=Pt(20), indent_ch=2)
            cell_para(cell, body, cn="SimSun", size=10.5,
                      before=0, after=0, line=Pt(20), indent_ch=2)

        cell_para(cell, "（二）教训反思", cn="SimHei", size=10.5, bold=True,
                  before=4, after=1, line=Pt(20))
        items_b = [
            ("1. 前沿技术学习曲线的低估",
             "项目立项时，团队对多租户 SaaS 架构、RAG、Sa-Token 会话安全、SSRF 防御等前沿技术的学习曲线估计过于乐观，实际投入的学习与调试成本超出计划。建议后续类似项目在立项阶段预留更充分的技术专项学习期（不少于 2 周），并配套原型验证阶段以校准技术风险。"),
            ("2. AI 服务成本与治理复杂度的低估",
             "在集成 AI 能力时，团队最初未充分考虑到\"多模型、多租户、分级限流\"所带来的工程治理复杂度，也未充分预估云端商用模型按 Token 计费的成本压力。经过多轮迭代，最终形成了\"OpenAI 兼容协议 + 抽象接口 + 五级降级 + 三级限流 + SSRF 防御\"的治理方案，但研发投入远超原计划。建议后续在 AI 集成项目中将治理机制作为第一优先级设计。"),
            ("3. 推广渠道的局限性",
             "平台上线后的初期推广主要依赖校内熟人网络，尚未充分利用社交媒体、技术社区（如 CSDN、掘金、知乎、V2EX）、开源仓库星标增长机制等多元渠道，用户增长曲线较为平缓。后续将重点补充线上技术分享、参与开源活动等推广策略。"),
        ]
        for h, body in items_b:
            cell_para(cell, h, cn="SimHei", size=10.5, bold=True,
                      before=2, after=0, line=Pt(20), indent_ch=2)
            cell_para(cell, body, cn="SimSun", size=10.5,
                      before=0, after=0, line=Pt(20), indent_ch=2)

        cell_para(cell, "（三）自我评价", cn="SimHei", size=10.5, bold=True,
                  before=4, after=1, line=Pt(20))
        items_c = [
            ("1. 项目执行能力",
             "项目团队展现了较强的执行力与应变能力。面对多租户架构、AI 集成、安全加固等一系列高复杂度技术挑战，团队通过分阶段迭代、原型驱动、代码评审等方式稳步推进，最终按计划完成了平台开发与上线。"),
            ("2. 团队协作能力",
             "团队 5 名核心成员在项目周期内保持了紧密协作与高效沟通：项目负责人张官幸承担项目统筹与后端核心开发；林维迟承担前端页面与交互开发；易祖涛承担服务器运维与 DBA；林小意与石昌妮共同承担宣传推广与项目文档撰写工作。"),
            ("3. 技术创新意识",
             "在项目实施过程中，团队主动追求技术方案的创新性与工程严谨性，从多租户方案选型到 AI 治理体系，再到三重防越权设计，展现出较为成熟的架构思维与工程创新意识。"),
            ("4. 社会责任感与开源精神",
             "团队深刻认识到高校信息化建设\"数据自主可控、低成本可落地\"的重要性，因此在项目立项之初即明确以 MIT 协议开源，配套完整的部署与二次开发文档，希望通过自己的努力为全国高校特别是中小高校提供一款高质量的开源校园学习社群平台，为教育数字化战略行动贡献学生群体的力量。"),
        ]
        for h, body in items_c:
            cell_para(cell, h, cn="SimHei", size=10.5, bold=True,
                      before=2, after=0, line=Pt(20), indent_ch=2)
            cell_para(cell, body, cn="SimSun", size=10.5,
                      before=0, after=4, line=Pt(20), indent_ch=2)
    add_section_body(doc, _sec6)

    # ==================== 七 / 八 / 九、意见栏 ====================
    def blank_opinion(header_text, sign_label="指导教师签字：", height_lines=10):
        add_section_header(doc, header_text)

        def _fn(cell):
            for _ in range(height_lines):
                cell_para(cell, "", cn="SimSun", size=10.5, line=Pt(22))
            # 签字栏
            p = cell.add_paragraph()
            p.alignment = WD_ALIGN_PARAGRAPH.RIGHT
            p.paragraph_format.space_before = Pt(8)
            r = p.add_run(sign_label + "__________________")
            set_font(r, cn="SimSun", size=10.5)
            p = cell.add_paragraph()
            p.alignment = WD_ALIGN_PARAGRAPH.RIGHT
            r = p.add_run("        年   月   日")
            set_font(r, cn="SimSun", size=10.5)
        add_section_body(doc, _fn)

    blank_opinion("七、指导教师意见", "指导教师签字：", 8)
    blank_opinion("八、学院（部）检查意见", "签字（盖章）：", 6)
    blank_opinion("九、学校检查意见", "签字（盖章）：", 6)

    # ==================== 附录说明 & 脚注 ====================
    add_p(doc, "附录 1：桂林电子科技大学大学生创新训练计划项目成果支撑材料",
          cn="SimSun", size=10.5, before=12, after=2, line=Pt(20))
    add_p(doc, "附录 2：大创项目结题成果数据统计表",
          cn="SimSun", size=10.5, before=0, after=6, line=Pt(20))
    add_p(doc, "*本表打印部分统一使用宋体，五号字，单倍行距；表格部分采用正反面套印装订。",
          cn="SimSun", size=10.5, before=6, after=0, line=Pt(20))

    doc.save(str(OUT))
    print(f"✅ 已生成 {OUT}")


if __name__ == "__main__":
    build()
