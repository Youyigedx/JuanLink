# -*- coding: utf-8 -*-
"""
生成《合作开发协议》的打印用 PDF（A4、宋体正文、黑体标题、页脚页码）。

为什么不直接用 officecli 的 pdf 导出
  - 需要额外的 exporter 插件，本机未安装
  - 本机也没有 Word / LibreOffice 可做 docx → pdf

为什么用 reportlab
  - 中文排版可控：可指定宋体/黑体、首行缩进 2 字符、1.5 倍行距
  - 页脚「第 X 页 共 Y 页」可精确控制（两遍构建拿到总页数）

用法：
  python tools/make_agreement_pdf.py [输出路径]
"""

import os
import sys

from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import cm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (BaseDocTemplate, Frame, PageBreak, PageTemplate,
                                Paragraph, Spacer)

FONT_DIR = r"C:\Windows\Fonts"
SONG = "SimSun"
HEI = "SimHei"


def register_fonts():
    """注册宋体与黑体。simsun.ttc 是字体集合，需指定 subfontIndex。"""
    pdfmetrics.registerFont(TTFont(SONG, os.path.join(FONT_DIR, "simsun.ttc"), subfontIndex=0))
    pdfmetrics.registerFont(TTFont(HEI, os.path.join(FONT_DIR, "simhei.ttf")))


# ---------------------------------------------------------------- 版式

PAGE_W, PAGE_H = A4
MARGIN_L = MARGIN_R = 3.17 * cm
MARGIN_T = 2.54 * cm
MARGIN_B = 2.54 * cm
FOOTER_GAP = 1.0 * cm

BODY_SIZE = 12
BODY_LEADING = 22          # 接近 Word 的 1.5 倍行距观感
INDENT = BODY_SIZE * 2     # 首行缩进 2 个字符


def styles():
    return {
        "title": ParagraphStyle("title", fontName=HEI, fontSize=22, leading=30,
                                alignment=TA_CENTER, spaceAfter=20, wordWrap="CJK"),
        "h_party": ParagraphStyle("h_party", fontName=HEI, fontSize=12, leading=20,
                                  alignment=TA_LEFT, spaceBefore=12, spaceAfter=6, wordWrap="CJK"),
        "info": ParagraphStyle("info", fontName=SONG, fontSize=BODY_SIZE,
                               leading=BODY_LEADING, alignment=TA_LEFT, wordWrap="CJK"),
        "h_article": ParagraphStyle("h_article", fontName=HEI, fontSize=14, leading=22,
                                    alignment=TA_LEFT, spaceBefore=14, spaceAfter=8,
                                    keepWithNext=1, wordWrap="CJK"),
        "body": ParagraphStyle("body", fontName=SONG, fontSize=BODY_SIZE,
                               leading=BODY_LEADING, alignment=TA_LEFT,
                               firstLineIndent=INDENT, wordWrap="CJK"),
        "center": ParagraphStyle("center", fontName=SONG, fontSize=BODY_SIZE,
                                 leading=BODY_LEADING, alignment=TA_CENTER, wordWrap="CJK"),
        "sig": ParagraphStyle("sig", fontName=SONG, fontSize=BODY_SIZE,
                              leading=BODY_LEADING, alignment=TA_LEFT, wordWrap="CJK"),
        "h_appendix": ParagraphStyle("h_appendix", fontName=HEI, fontSize=14, leading=22,
                                     alignment=TA_LEFT, spaceAfter=10, keepWithNext=1, wordWrap="CJK"),
        "apx_sub": ParagraphStyle("apx_sub", fontName=HEI, fontSize=12, leading=20,
                                  alignment=TA_LEFT, spaceBefore=12, spaceAfter=4, wordWrap="CJK"),
        "apx_body": ParagraphStyle("apx_body", fontName=SONG, fontSize=BODY_SIZE,
                                   leading=BODY_LEADING, alignment=TA_LEFT,
                                   firstLineIndent=INDENT, wordWrap="CJK"),
    }


# ---------------------------------------------------------------- 内容

# (样式, 文本)；样式为 "spacer:<pt>" 时插入垂直间距
CONTENT = [
    ("title", "合作开发协议"),

    ("h_party", "甲方（第一著作权人）"),
    ("info", "姓名：＿＿＿＿＿＿　　身份证号：＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿"),
    ("info", "通讯地址：＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿"),
    ("info", "联系电话：＿＿＿＿＿＿＿＿＿＿＿"),

    ("h_party", "乙方（第二著作权人）"),
    ("info", "姓名：吴芸（Wu Yun）"),
    ("info", "身份证号：＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿"),
    ("info", "通讯地址：＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿"),
    ("info", "联系电话：＿＿＿＿＿＿＿＿＿＿＿"),

    ("body", "鉴于甲乙双方共同参与开发下列计算机软件，为明确该计算机软件著作权的归属与行使，"
             "依据《中华人民共和国著作权法》《计算机软件保护条例》及有关法律法规的规定，"
             "经双方平等协商，达成如下协议："),

    ("h_article", "第一条　软件名称与版本"),
    ("body", "1.1　软件全称：Juan LinK（结尾字母 K 为大写）。"),
    ("body", "1.2　版本号：V1.0。"),
    ("body", "1.3　软件用途：端到端加密的点对点协作画板软件，含配对信令（会合服务）子系统。"),
    ("body", "1.4　运行平台：桌面端（Windows、macOS、Linux）；移动端（Android 13／API 33 及以上）。"),

    ("h_article", "第二条　开发方式"),
    ("body", "2.1　双方确认，本软件系双方共同开发的成果。"),
    ("body", "2.2　双方确认，本软件的开发方式为合作开发；本软件非一方委托另一方开发，"
             "亦非任何单位的职务开发成果。"),

    ("h_article", "第三条　分工与贡献"),
    ("body", "3.1　甲方负责技术设计与编码实现，具体包括：软件架构设计，端到端加密与操作同步机制的实现，"
             "桌面端与 Android 端编码，配对信令（会合服务）实现，以及全部源代码的编写与提交。"),
    ("body", "3.2　乙方负责产品设计与测试验收，具体包括：功能需求梳理，交互与界面设计，"
             "测试用例设计与执行，缺陷跟踪与验收。"),
    ("body", "3.3　双方确认，上述分工如实反映各自的实际贡献。计算机软件著作权的保护对象为软件作品，"
             "需求分析、界面设计、测试验收均属软件开发活动。"),

    ("h_article", "第四条　著作权归属"),
    ("body", "4.1　本软件的著作权，包括《计算机软件保护条例》第八条规定的发表权、署名权、修改权、"
             "复制权、发行权、信息网络传播权等各项权利，由甲乙双方共同享有。"),
    ("body", "4.2　双方约定的著作权份额为：甲方 ＿＿＿％，乙方 ＿＿＿％。"
             "本款未填写的，视为双方等额共有。"),
    ("body", "4.3　双方同意，在计算机软件著作权登记申请中，申请表的著作权人顺序为："
             "第一著作权人为甲方，第二著作权人为乙方。"),
    ("body", "4.4　任何一方均有权在自身履历、业绩、成果申报中如实陈述其与本软件的关系，"
             "但不得作出与本协议约定相抵触的陈述。"),

    ("h_article", "第五条　登记申请"),
    ("body", "5.1　双方一致同意，以本软件申请计算机软件著作权登记，并以双方名义作为共同著作权人提出申请。"),
    ("body", "5.2　双方同意委托 ＿＿＿＿＿＿＿＿（甲方／乙方）作为办理人，负责提交并跟进登记申请；"
             "办理人应将申请进展及时告知另一方。"),
    ("body", "5.3　登记所需材料，包括双方的身份证件、源程序、文档等，由双方按需配合提供。"),
    ("body", "5.4　登记获准后，登记证书由 ＿＿＿＿＿＿＿＿ 保管，另一方有权获得证书复印件。"),

    ("h_article", "第六条　权利的行使"),
    ("body", "6.1　任何一方对外许可他人使用本软件，或转让其享有的著作权份额，须经另一方书面同意；"
             "所得收益由双方协商分配。"),
    ("body", "6.2　一方发现第三方侵犯本软件著作权时，应及时通知另一方；维权以双方共同名义进行，"
             "所需费用与所得赔偿按第四条约定份额分担与分配。"),
    ("body", "6.3　任何一方不得单独将本软件的全部著作权转让给第三方，"
             "亦不得单独作出放弃著作权的意思表示。"),

    ("h_article", "第七条　开源许可的一致性"),
    ("body", "7.1　双方确认，本软件当前以 MIT 许可证公开，许可全文见软件代码仓库根目录下的 LICENSE 文件。"),
    ("body", "7.2　双方均知悉：依 MIT 许可证，任何第三方已获得免费使用、复制、修改、分发本软件的权利。"),
    ("body", "7.3　双方同意：是否继续沿用开源许可，或是否改为保留所有权利，须经双方协商一致；"
             "任何一方不得单方变更。"),
    ("body", "7.4　双方知悉：依 MIT 许可证，任一著作权人对外作出的授权即可生效。"
             "因此双方应就是否开源保持口径一致，避免出现一方主张专有、另一方已对外授权的冲突。"),

    ("h_article", "第八条　保密"),
    ("body", "8.1　双方对开发过程中知悉的对方未公开信息负有保密义务，该义务不因本协议终止而免除。"),
    ("body", "8.2　前款不适用于已公开的信息，或依法律、监管要求须披露的信息。"),

    ("h_article", "第九条　争议解决"),
    ("body", "9.1　因本协议引起的或与本协议有关的争议，双方应友好协商解决。"),
    ("body", "9.2　协商不成的，任何一方可向 ＿＿＿＿＿＿＿＿＿＿ 人民法院提起诉讼。"),

    ("h_article", "第十条　其他"),
    ("body", "10.1　本协议自双方签字之日起生效。"),
    ("body", "10.2　本协议一式两份，双方各执一份，具有同等法律效力。"),
    ("body", "10.3　本协议未尽事宜，由双方另行协商并以书面补充协议确定；"
             "补充协议与本协议具有同等法律效力。"),

    ("center", "（以下无正文）"),
    ("spacer", "26"),
    ("sig", "甲方（签字）：＿＿＿＿＿＿＿＿＿＿"),
    ("spacer", "22"),
    ("sig", "乙方（签字）：＿＿＿＿＿＿＿＿＿＿"),
    ("spacer", "26"),
    ("sig", "签署日期：＿＿＿＿ 年 ＿＿ 月 ＿＿ 日"),

    ("pagebreak", ""),
    ("h_appendix", "附录　填写与提交说明（本页不构成协议正文，签署后可移除）"),
    ("apx_sub", "一、需手写填写的空白项"),
    ("apx_body", "1. 甲方姓名、身份证号、通讯地址、联系电话。"),
    ("apx_body", "2. 乙方身份证号、通讯地址、联系电话。"),
    ("apx_body", "3. 第四条第 4.2 款的著作权份额（未填写则视为等额共有）。"),
    ("apx_body", "4. 第五条的登记办理人，以及登记证书保管人。"),
    ("apx_body", "5. 第九条的争议管辖法院。"),
    ("apx_body", "6. 双方签字与签署日期。"),
    ("apx_sub", "二、提交登记前须核对"),
    ("apx_body", "1. 两位著作权人姓名与身份证件逐字一致（含生僻字、繁简体）。"),
    ("apx_body", "2. 软件名称「Juan LinK」与申请表、源程序页眉、文档页眉完全一致（结尾字母 K 为大写）。"),
    ("apx_body", "3. 版本号「V1.0」与申请表、页眉一致。"),
    ("apx_body", "4. 本协议一式两份，均须双方手写签名；提交登记时另附复印件或扫描件。"),
    ("apx_body", "5. 协议第三条对分工的表述，应与可核查的开发记录相符，"
                 "不宜笼统写为双方共同完成全部开发工作。"),
    ("apx_sub", "三、声明"),
    ("apx_body", "本模板仅为便于准备登记材料而提供，不构成法律意见。"
                 "涉及著作权份额、对外许可、争议管辖等重大事项，建议签署前咨询专业人士。"),
]


def build_pdf(out_path, total_pages_ref=None):
    """构建 PDF。total_pages_ref 为单元素列表时用于回填总页数（两遍构建）。"""
    st = styles()
    doc = BaseDocTemplate(
        out_path, pagesize=A4,
        leftMargin=MARGIN_L, rightMargin=MARGIN_R,
        topMargin=MARGIN_T, bottomMargin=MARGIN_B,
        title="合作开发协议", author="赵裕轩、吴芸",
        subject="计算机软件著作权登记申请材料之合作开发协议",
    )

    def on_page(canvas, doc_):
        canvas.saveState()
        canvas.setFont(SONG, 9)
        total = total_pages_ref[0] if total_pages_ref else ""
        txt = f"第 {doc_.page} 页　共 {total} 页"
        canvas.drawCentredString(PAGE_W / 2.0, MARGIN_B - FOOTER_GAP / 2.0, txt)
        canvas.restoreState()

    frame = Frame(
        MARGIN_L, MARGIN_B, PAGE_W - MARGIN_L - MARGIN_R,
        PAGE_H - MARGIN_T - MARGIN_B, id="body",
        leftPadding=0, rightPadding=0, topPadding=0, bottomPadding=0,
    )
    doc.addPageTemplates([PageTemplate(id="main", frames=[frame], onPage=on_page)])

    flow = []
    for key, text in CONTENT:
        if key == "spacer":
            flow.append(Spacer(1, float(text)))
        elif key == "pagebreak":
            flow.append(PageBreak())
        else:
            flow.append(Paragraph(text, st[key]))
    doc.build(flow)
    return doc.page


def main(argv):
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = argv[0] if argv else os.path.join(root, "out", "软著", "合作开发协议.pdf")
    os.makedirs(os.path.dirname(out), exist_ok=True)

    register_fonts()

    # 两遍构建：第一遍只为拿到总页数，供页脚「共 Y 页」使用。
    # 页脚画在正文框之外，不影响分页，故第二遍页数不变。
    tmp = out + ".tmp.pdf"
    total = build_pdf(tmp)
    os.remove(tmp)
    build_pdf(out, total_pages_ref=[total])

    print(f"已生成  {out}")
    print(f"总页数  {total}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
