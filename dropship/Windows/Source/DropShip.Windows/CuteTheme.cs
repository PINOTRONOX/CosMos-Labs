using System.Drawing.Drawing2D;

namespace DropShip.Windows;

internal static class CuteTheme {
    public static readonly Color Purple=Color.FromArgb(109,79,196), Ink=Color.FromArgb(52,43,78), Muted=Color.FromArgb(110,101,130), Paper=Color.FromArgb(248,246,255), Lavender=Color.FromArgb(239,232,255), Mint=Color.FromArgb(225,247,239);
    public static GraphicsPath Round(RectangleF r,float radius) {
        var p=new GraphicsPath();float d=Math.Min(radius*2,Math.Min(r.Width,r.Height));
        p.AddArc(r.Left,r.Top,d,d,180,90);p.AddArc(r.Right-d,r.Top,d,d,270,90);p.AddArc(r.Right-d,r.Bottom-d,d,d,0,90);p.AddArc(r.Left,r.Bottom-d,d,d,90,90);p.CloseFigure();return p;
    }
    public static void Apply(Control parent) {
        foreach(Control c in parent.Controls) {
            c.ForeColor=Ink;
            if(c is TabPage) c.BackColor=Paper;
            if(c is ListView l){l.BackColor=Color.White;l.BorderStyle=BorderStyle.None;l.GridLines=false;l.HeaderStyle=ColumnHeaderStyle.Nonclickable;}
            if(c is ListBox list){list.BackColor=Color.White;list.BorderStyle=BorderStyle.FixedSingle;list.ItemHeight=30;}
            if(c is CuteButton b){b.Primary=b.Text.Contains("보내기 / 이어보내기");b.Danger=b.Text.Contains("취소")||b.Text.Contains("해제");b.Padding=new(13,8,13,8);b.Margin=new(3,4,6,4);b.MinimumSize=new(0,42);b.Cursor=Cursors.Hand;}
            Apply(c);
        }
    }
    public static void Ship(Graphics g,RectangleF area) {
        var saved=g.Save();float scale=Math.Min(area.Width/160f,area.Height/140f);g.TranslateTransform(area.X+(area.Width-160*scale)/2,area.Y+(area.Height-140*scale)/2);g.ScaleTransform(scale,scale);g.SmoothingMode=SmoothingMode.AntiAlias;
        void Oval(Color color,float x,float y,float w,float h){using var b=new SolidBrush(color);g.FillEllipse(b,x,y,w,h);}
        void Line(Color color,float x,float y,float x2,float y2){using var p=new Pen(color,3){StartCap=LineCap.Round,EndCap=LineCap.Round};g.DrawLine(p,x,y,x2,y2);}
        Oval(Color.FromArgb(231,223,250),24,117,112,13);Oval(Color.FromArgb(255,220,155),63,90,34,31);Oval(Color.FromArgb(255,242,203),71,88,18,27);
        Oval(Color.FromArgb(199,238,225),39,17,82,80);Oval(Color.FromArgb(239,255,247),48,24,24,25);
        Oval(Purple,16,65,128,39);Oval(Color.FromArgb(184,160,235),16,58,128,33);Oval(Color.FromArgb(224,211,255),34,65,92,19);
        Oval(Ink,63,47,6,9);Oval(Ink,91,47,6,9);Oval(Color.FromArgb(246,171,190),52,56,13,6);Oval(Color.FromArgb(246,171,190),96,56,13,6);
        using(var smile=new Pen(Ink,2.5f){StartCap=LineCap.Round,EndCap=LineCap.Round})g.DrawArc(smile,73,51,14,11,15,150);
        foreach(int x in new[]{47,77,107})Oval(Color.FromArgb(255,242,191),x,86,7,7);
        Line(Color.FromArgb(178,153,225),20,28,20,40);Line(Color.FromArgb(178,153,225),14,34,26,34);Line(Color.FromArgb(230,173,96),139,46,139,56);Line(Color.FromArgb(230,173,96),134,51,144,51);Oval(Color.FromArgb(168,204,191),128,19,5,5);g.Restore(saved);
    }
}

public sealed class CuteButton:Button {
    public bool Primary{get;set;} public bool Danger{get;set;} bool hover;
    public CuteButton(){SetStyle(ControlStyles.UserPaint|ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer,true);FlatStyle=FlatStyle.Flat;FlatAppearance.BorderSize=0;UseVisualStyleBackColor=false;}
    protected override void OnMouseEnter(EventArgs e){hover=true;Invalidate();base.OnMouseEnter(e);}
    protected override void OnMouseLeave(EventArgs e){hover=false;Invalidate();base.OnMouseLeave(e);}
    protected override void OnEnabledChanged(EventArgs e){base.OnEnabledChanged(e);Invalidate();}
    protected override void OnPaint(PaintEventArgs e){
        e.Graphics.Clear(Parent?.BackColor??CuteTheme.Paper);e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;
        var fill=!Enabled?Color.FromArgb(239,237,244):Primary?CuteTheme.Purple:Danger?Color.FromArgb(255,239,241):CuteTheme.Lavender;
        if(hover&&Enabled)fill=ControlPaint.Dark(fill,.04f);
        using var path=CuteTheme.Round(new RectangleF(0,0,Width-1,Height-1),14*DeviceDpi/96f);using var brush=new SolidBrush(fill);e.Graphics.FillPath(brush,path);
        var color=!Enabled?Color.FromArgb(149,143,161):Primary?Color.White:Danger?Color.FromArgb(158,54,79):CuteTheme.Purple;
        TextRenderer.DrawText(e.Graphics,Text,Font,ClientRectangle,color,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter|TextFormatFlags.WordBreak);
        if(Focused){using var pen=new Pen(CuteTheme.Purple,2){DashStyle=DashStyle.Dot};using var focus=CuteTheme.Round(new RectangleF(3,3,Width-7,Height-7),11);e.Graphics.DrawPath(pen,focus);}
    }
}

public sealed class CuteProgress:Control {
    int progress;public int Maximum{get;set;}=1000;
    public int Value{get=>progress;set{progress=Math.Clamp(value,0,Maximum);Invalidate();}}
    public CuteProgress(){SetStyle(ControlStyles.UserPaint|ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer,true);Height=12;AccessibleName="전송 진행률";}
    protected override void OnPaint(PaintEventArgs e){e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;using var track=CuteTheme.Round(new RectangleF(0,0,Width-1,Height-1),Height/2f);using var bg=new SolidBrush(CuteTheme.Lavender);e.Graphics.FillPath(bg,track);float w=(Width-1)*Value/(float)Math.Max(1,Maximum);if(w>1){using var path=CuteTheme.Round(new RectangleF(0,0,w,Height-1),Height/2f);using var ink=new SolidBrush(CuteTheme.Purple);e.Graphics.FillPath(ink,path);}}
}

public sealed class HeroHeader:Control {
    public HeroHeader(){Height=106;Dock=DockStyle.Top;DoubleBuffered=true;}
    protected override void OnPaint(PaintEventArgs e){base.OnPaint(e);var g=e.Graphics;float s=DeviceDpi/96f;CuteTheme.Ship(g,new RectangleF(10*s,0,125*s,104*s));using var title=new Font("맑은 고딕",25,FontStyle.Bold);using var subtitle=new Font("맑은 고딕",10);TextRenderer.DrawText(g,"DropShip",title,new Point((int)(145*s),(int)(15*s)),CuteTheme.Ink);TextRenderer.DrawText(g,"파일을 싣고, 사뿐!  ·  우리 기기 사이 작은 배송",subtitle,new Point((int)(148*s),(int)(61*s)),CuteTheme.Muted);using var badge=new SolidBrush(CuteTheme.Lavender);using var path=CuteTheme.Round(new RectangleF(Width-128*s,31*s,105*s,34*s),16*s);g.FillPath(badge,path);TextRenderer.DrawText(g,"v0.7.1  COSRAI",subtitle,new Rectangle((int)(Width-128*s),(int)(31*s),(int)(105*s),(int)(34*s)),CuteTheme.Purple,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);}
}

public sealed class DropHint:Control {
    public DropHint(){Dock=DockStyle.Fill;DoubleBuffered=true;BackColor=Color.White;AccessibleName="파일이나 폴더를 여기에 끌어 놓으세요";}
    protected override void OnPaint(PaintEventArgs e){base.OnPaint(e);float s=DeviceDpi/96f;var g=e.Graphics;g.SmoothingMode=SmoothingMode.AntiAlias;using var pen=new Pen(Color.FromArgb(219,207,243),2){DashStyle=DashStyle.Dash};using var path=CuteTheme.Round(new RectangleF(8,8,Width-17,Height-17),20);g.DrawPath(pen,path);float h=Math.Min(105*s,Math.Max(40,Height-65*s));CuteTheme.Ship(g,new RectangleF(Width/2-65*s,Math.Max(12,Height/2-h/2-25*s),130*s,h));using var f=new Font("맑은 고딕",12,FontStyle.Bold);using var small=new Font("맑은 고딕",9);TextRenderer.DrawText(g,"파일·폴더를 여기에 톡!",f,new Rectangle(10,(int)(Height/2+h/2-22*s),Width-20,(int)(28*s)),CuteTheme.Ink,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);TextRenderer.DrawText(g,"아래 추가 버튼으로 담아도 좋아요",small,new Rectangle(10,(int)(Height/2+h/2+6*s),Width-20,(int)(24*s)),CuteTheme.Muted,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);}
}
