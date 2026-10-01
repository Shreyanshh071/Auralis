
"use strict";
if (!process.env.FIRESTORE_EMULATOR_HOST) throw Error("Firestore emulator required; production forbidden");
const test=require("node:test");const assert=require("node:assert/strict");const fs=require("node:fs");
const {initializeApp}=require("firebase-admin/app");const {getFirestore}=require("firebase-admin/firestore");
const {initializeTestEnvironment,assertFails}=require("@firebase/rules-unit-testing");
const {doc,collection,query,where,getDoc,getDocs,setDoc,updateDoc,deleteDoc,arrayRemove,arrayUnion,runTransaction}=require("firebase/firestore");
const projectId="demo-auralis-room-cleanup";const admin=getFirestore(initializeApp({projectId}));let env;
test.before(async()=>{env=await initializeTestEnvironment({projectId,firestore:{rules:fs.readFileSync("../firestore.rules","utf8")}})});
test.after(async()=>{await env.cleanup();await admin.terminate()});
function client(uid){return env.authenticatedContext(uid).firestore()}
async function seed(code){
 await admin.doc(`rooms/${code}`).set({hostId:"host",status:"active"});
 await admin.doc(`rooms/${code}/members/host`).set({id:"host",joinedAt:1});
 await admin.doc(`rooms/${code}/members/guest`).set({id:"guest",joinedAt:2});
 await admin.doc(`rooms/${code}/members/other`).set({id:"other",joinedAt:3});
 await admin.doc(`rooms/${code}/recommendations/guest-request`).set({recommendedByUid:"guest",upvotes:["guest","other"],track:{id:"requested-song"}});
 await admin.doc(`rooms/${code}/recommendations/other-request`).set({recommendedByUid:"other",upvotes:["guest","other"],track:{id:"other-song"}});
}
async function leaveGuest(db,code){
 const votes=query(collection(db,`rooms/${code}/recommendations`),where("upvotes","array-contains","guest"));
 for(const rec of (await getDocs(votes)).docs)await updateDoc(rec.ref,{upvotes:arrayRemove("guest")});
 assert.equal((await getDocs(votes)).empty,true);
 await deleteDoc(doc(db,`rooms/${code}/members/guest`));
}
test("normal guest leave keeps requests and other votes, including retry after membership removal",async()=>{
 const code="GUEST";await seed(code);const db=client("guest");await leaveGuest(db,code);await leaveGuest(db,code);
 assert.equal((await admin.doc(`rooms/${code}`).get()).exists,true);
 assert.equal((await admin.doc(`rooms/${code}/members/guest`).get()).exists,false);
 assert.equal((await admin.doc(`rooms/${code}/members/other`).get()).exists,true);
 for(const id of ["guest-request","other-request"]){const d=await admin.doc(`rooms/${code}/recommendations/${id}`).get();assert.equal(d.exists,true);assert.deepEqual(d.get("upvotes"),["other"])}
 await assertFails(updateDoc(doc(db,`rooms/${code}/recommendations/other-request`),{upvotes:arrayUnion("guest")}));
});
test("departed guest can remove only their own remaining vote, never another user's",async()=>{
 const code="RETRY";await seed(code);await admin.doc(`rooms/${code}/members/guest`).delete();const db=client("guest");
 await assertFails(updateDoc(doc(db,`rooms/${code}/recommendations/other-request`),{upvotes:[]}));
 await leaveGuest(db,code);
 assert.deepEqual((await admin.doc(`rooms/${code}/recommendations/other-request`).get()).get("upvotes"),["other"]);
});
test("host closes before child deletion; closed/missing room cannot gain new membership or requests",async()=>{
 const code="HOST";await seed(code);const db=client("host");await updateDoc(doc(db,`rooms/${code}`),{status:"closed"});
 const guest=client("guest");
 await assertFails(setDoc(doc(guest,`rooms/${code}/members/guest`),{id:"guest",name:"Guest",isHost:false,lastSeen:1}));
 await assertFails(setDoc(doc(guest,`rooms/${code}/recommendations/new`),{id:"new",recommendedByUid:"guest",recommendedByName:"Guest",upvotes:["guest"],status:"pending",track:{},note:"",createdAt:1}));
 for(const rec of (await getDocs(collection(db,`rooms/${code}/recommendations`))).docs)await deleteDoc(rec.ref);
 assert.equal((await getDocs(collection(db,`rooms/${code}/recommendations`))).empty,true);
 await deleteDoc(doc(db,`rooms/${code}`));
 // Simulate a process restart after parent deletion: the saved task still has this room code.
 for(const member of (await getDocs(collection(db,`rooms/${code}/members`))).docs)await deleteDoc(member.ref);
 assert.equal((await getDocs(collection(db,`rooms/${code}/members`))).empty,true);
 assert.equal((await admin.doc(`rooms/${code}`).get()).exists,false);
 assert.equal((await admin.collection(`rooms/${code}/recommendations`).get()).empty,true);
});
test("unrelated signed-in user cannot delete active room members or requests",async()=>{
 const code="SAFE";await seed(code);const db=client("stranger");
 await assertFails(deleteDoc(doc(db,`rooms/${code}/members/guest`)));
 await assertFails(deleteDoc(doc(db,`rooms/${code}/recommendations/guest-request`)));
 await assertFails(getDocs(collection(db,`rooms/${code}/recommendations`)));
});

test("stale guest cleanup transaction preserves a newly joined generation and its votes",async()=>{
 const code="REJOIN";await seed(code);const db=client("guest");
 await admin.doc(`rooms/${code}/members/guest`).update({joinedAt:200});
 await runTransaction(db,async tx=>{
  const member=doc(db,`rooms/${code}/members/guest`);const own=await tx.get(member);
  const rec=doc(db,`rooms/${code}/recommendations/guest-request`);const current=await tx.get(rec);
  if(own.exists()&&own.get("joinedAt")!==2)return;
  if(current.exists())tx.update(rec,{upvotes:arrayRemove("guest")});
  if(own.exists())tx.delete(member);
 });
 assert.equal((await admin.doc(`rooms/${code}/members/guest`).get()).get("joinedAt"),200);
 assert.deepEqual((await admin.doc(`rooms/${code}/recommendations/guest-request`).get()).get("upvotes"),["guest","other"]);
});
