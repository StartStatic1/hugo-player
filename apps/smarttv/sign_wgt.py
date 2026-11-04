import os,sys,hashlib,base64,zipfile,urllib.parse
from lxml import etree
from cryptography.hazmat.primitives.serialization import pkcs12,Encoding
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding
DS="http://www.w3.org/2000/09/xmldsig#"
def b64(b): return base64.b64encode(b).decode()
def files(root):
    out=[]
    for d,_,fs in os.walk(root):
        for f in fs:
            p=os.path.relpath(os.path.join(d,f),root)
            if p=="author-signature.xml" or (p.startswith("signature") and p.endswith(".xml")): continue
            out.append(p)
    return sorted(out)
def sign(root,p12,pw,sid,role,fname,extra=None,cafiles=()):
    key,cert,chain=pkcs12.load_key_and_certificates(open(p12,'rb').read(),pw.encode())
    certs=[cert]+list(chain or [])
    for c in cafiles:
        from cryptography import x509
        certs.append(x509.load_pem_x509_certificate(open(c,'rb').read()) if open(c,'rb').read().startswith(b'-----') else x509.load_der_x509_certificate(open(c,'rb').read()))
    E=lambda t,**a: etree.Element("{%s}%s"%(DS,t),nsmap={None:DS},**a)
    sig=E("Signature",Id=sid); si=etree.SubElement(sig,"{%s}SignedInfo"%DS)
    etree.SubElement(si,"{%s}CanonicalizationMethod"%DS,Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#")
    etree.SubElement(si,"{%s}SignatureMethod"%DS,Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256")
    def ref(uri,digest,tr=None):
        r=etree.SubElement(si,"{%s}Reference"%DS,URI=uri)
        if tr:
            ts=etree.SubElement(r,"{%s}Transforms"%DS); etree.SubElement(ts,"{%s}Transform"%DS,Algorithm=tr)
        etree.SubElement(r,"{%s}DigestMethod"%DS,Algorithm="http://www.w3.org/2001/04/xmlenc#sha256")
        etree.SubElement(r,"{%s}DigestValue"%DS).text=digest
    fl=files(root)+(extra or [])
    if fname=="signature1.xml": fl=sorted(fl)
    for f in fl:
        ref(urllib.parse.quote(f.replace(os.sep,'/')),b64(hashlib.sha256(open(os.path.join(root,f),'rb').read()).digest()))
    sv=etree.SubElement(sig,"{%s}SignatureValue"%DS)
    ki=etree.SubElement(sig,"{%s}KeyInfo"%DS); xd=etree.SubElement(ki,"{%s}X509Data"%DS)
    for c in certs: etree.SubElement(xd,"{%s}X509Certificate"%DS).text=b64(c.public_bytes(Encoding.DER))
    obj=etree.SubElement(sig,"{%s}Object"%DS,Id="prop")
    DSP="http://www.w3.org/2009/xmldsig-properties"
    sp=etree.SubElement(obj,"{%s}SignatureProperties"%DS,nsmap={"dsp":DSP})
    for i,tag,uri in [("profile","Profile","http://www.w3.org/ns/widgets-digsig#profile"),("role","Role","http://www.w3.org/ns/widgets-digsig#role-"+role),("identifier","Identifier",None)]:
        p=etree.SubElement(sp,"{%s}SignatureProperty"%DS,Id=i,Target="#"+sid); e=etree.SubElement(p,"{%s}%s"%(DSP,tag))
        if uri: e.set("URI",uri)
    # #prop reference must be inside SignedInfo before signing
    pd=b64(hashlib.sha256(etree.tostring(obj,method="c14n")).digest())
    ref("#prop",pd,"http://www.w3.org/2006/12/xml-c14n11")
    c=etree.tostring(si,method="c14n",exclusive=True)
    sv.text=b64(key.sign(c,padding.PKCS1v15(),hashes.SHA256()))
    open(os.path.join(root,fname),'wb').write(etree.tostring(sig))
root,out=sys.argv[1],sys.argv[2]
ap=open(os.path.expanduser(sys.argv[3])).read().strip()
D=os.environ.get("DIST_DIR","/home/box/tizen-studio/tools/certificate-generator/certificates/distributor/")
sign(root,os.environ.get("AUTHOR_P12","/home/box/SamsungCertificate/hugosamsung/author.p12"),ap,"AuthorSignature","author","author-signature.xml")
sign(root,D+"tizen-distributor-signer.p12","tizenpkcs12passfordsigner","DistributorSignature","distributor","signature1.xml",extra=["author-signature.xml"],cafiles=[D+"tizen-distributor-ca.cer"])
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
    for d,_,fs in os.walk(root):
        for f in fs: z.write(os.path.join(d,f),os.path.relpath(os.path.join(d,f),root))
print("ok")
